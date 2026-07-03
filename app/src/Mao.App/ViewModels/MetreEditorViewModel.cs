using System.Collections.ObjectModel;
using System.Linq;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Mao.Data;
using Mao.Domain.Entities;
using Mao.Domain.Services;
using Mao.Reports;

namespace Mao.App.ViewModels;

/// <summary>Éditeur d'un métré : hiérarchie aplatie en grille + totaux temps réel.</summary>
public partial class MetreEditorViewModel : ObservableObject
{
    private readonly MetreService _service;
    private readonly DechetService? _dechets;
    private readonly MetreCalculator _calc;
    public Metre Metre { get; }

    [ObservableProperty] private string _messageInfo = string.Empty;

    public ObservableCollection<PosteRowViewModel> Lignes { get; } = new();

    [ObservableProperty] private PosteRowViewModel? _ligneSelectionnee;

    [ObservableProperty] private decimal _totalHtva;
    [ObservableProperty] private decimal _totalTva;
    [ObservableProperty] private decimal _totalTtc;

    /// <summary>Demandé par « + Poste normalisé » : la vue ouvre le sélecteur de catalogue.</summary>
    public event Action? CatalogueDemande;

    /// <summary>Demande d'export : (type de document, format « pdf » ou « csv »). La vue choisit le fichier.</summary>
    public event Action<TypeDocument, string>? ExportDemande;

    /// <summary>Demande d'affichage des formules de révision du métré.</summary>
    public event Action? RevisionDemande;

    [RelayCommand]
    private void VoirFormulesRevision() => RevisionDemande?.Invoke();

    public MetreEditorViewModel(MetreService service, Metre metre, DechetService? dechets = null)
    {
        _service = service;
        _dechets = dechets;
        _calc = service.CreerCalculateur();
        Metre = metre;

        ReconstruireLignes();
        Recalculer();
    }

    /// <summary>Reconstruit la grille depuis la hiérarchie du métré (après génération/import).</summary>
    private void ReconstruireLignes()
    {
        foreach (var l in Lignes) l.Modifie -= Recalculer;
        Lignes.Clear();
        foreach (var div in Metre.Divisions.OrderBy(d => d.Numero))
            foreach (var chap in div.Chapitres.OrderBy(c => c.Numero))
                foreach (var poste in chap.Postes.OrderBy(p => p.Numero))
                    AjouterLigne(poste, chap, div);
    }

    /// <summary>Génération des postes déchets D9000 (reproduit MAO V8).</summary>
    [RelayCommand]
    private void GenererPostesDechets()
    {
        if (_dechets is null) { MessageInfo = "Service déchets indisponible."; return; }
        var rapport = _dechets.Generer(Metre);
        ReconstruireLignes();
        Recalculer();
        MessageInfo = rapport.Message;
    }

    public string Intitule
    {
        get => Metre.Intitule;
        set { Metre.Intitule = value; OnPropertyChanged(); }
    }

    private void AjouterLigne(Poste poste, Chapitre chap, Division div)
    {
        var ligne = new PosteRowViewModel(poste, chap, div);
        ligne.Modifie += Recalculer;
        Lignes.Add(ligne);
    }

    /// <summary>Division active = celle de la ligne sélectionnée, sinon la dernière.</summary>
    private Division? DivisionActive =>
        LigneSelectionnee?.Division ?? Metre.Divisions.OrderBy(d => d.Numero).LastOrDefault();

    private Chapitre? ChapitreActif =>
        LigneSelectionnee?.Chapitre
        ?? DivisionActive?.Chapitres.OrderBy(c => c.Numero).LastOrDefault();

    [RelayCommand]
    private void AjouterDivision()
    {
        var numero = (Metre.Divisions.Count == 0 ? 0 : Metre.Divisions.Max(d => d.Numero)) + 1;
        var div = new Division { MetreId = Metre.Id, Numero = numero, Intitule = $"Division {numero}" };
        Metre.Divisions.Add(div);
        AjouterChapitreDans(div);
    }

    [RelayCommand]
    private void AjouterChapitre()
    {
        var div = DivisionActive;
        if (div is null) { AjouterDivision(); return; }
        AjouterChapitreDans(div);
    }

    private void AjouterChapitreDans(Division div)
    {
        var numero = (div.Chapitres.Count == 0 ? 0 : div.Chapitres.Max(c => c.Numero)) + 1;
        var chap = new Chapitre { DivisionId = div.Id, Numero = numero, Intitule = $"Chapitre {numero}" };
        div.Chapitres.Add(chap);
    }

    [RelayCommand]
    private void AjouterPoste()
    {
        var chap = ChapitreActif;
        if (chap is null)
        {
            AjouterDivision();
            chap = ChapitreActif;
            if (chap is null) return;
        }
        var div = DivisionActive!;
        var numero = (chap.Postes.Count == 0 ? 0 : chap.Postes.Max(p => p.Numero)) + 1;
        var poste = new Poste
        {
            ChapitreId = chap.Id,
            Numero = numero,
            Intitule = "Nouveau poste",
            Unite = "u",
            QuantitePresumee = 0m,
            PrixUnitaire = 0m,
        };
        chap.Postes.Add(poste);
        AjouterLigne(poste, chap, div);
        Recalculer();
    }

    [RelayCommand]
    private void AjouterPosteNormalise() => CatalogueDemande?.Invoke();

    /// <summary>Insère un poste issu du catalogue normalisé dans le chapitre actif.</summary>
    public void InsererPosteNormalise(PosteStd std)
    {
        var chap = ChapitreActif;
        if (chap is null)
        {
            AjouterDivision();
            chap = ChapitreActif;
            if (chap is null) return;
        }
        var div = DivisionActive!;
        var numero = (chap.Postes.Count == 0 ? 0 : chap.Postes.Max(p => p.Numero)) + 1;
        var poste = new Poste
        {
            ChapitreId = chap.Id,
            Numero = numero,
            CodePosteStd = std.Code,
            Intitule = std.Intitule,
            Description = std.Description,
            Unite = std.Unite,
            TypePrix = std.TypePrix,
            EstNormalise = true,
            QuantitePresumee = 0m,
            PrixUnitaire = std.PrixUnitaireSuggere ?? 0m,
        };
        chap.Postes.Add(poste);
        AjouterLigne(poste, chap, div);
        Recalculer();
    }

    [RelayCommand]
    private void SupprimerPoste()
    {
        var ligne = LigneSelectionnee;
        if (ligne is null) return;
        ligne.Chapitre.Postes.Remove(ligne.Poste);
        ligne.Modifie -= Recalculer;
        Lignes.Remove(ligne);
        Recalculer();
    }

    [RelayCommand]
    private void Enregistrer() => _service.Enregistrer(Metre);

    [RelayCommand] private void ExporterBordereauPdf() => ExportDemande?.Invoke(TypeDocument.Bordereau, "pdf");
    [RelayCommand] private void ExporterEstimatifPdf() => ExportDemande?.Invoke(TypeDocument.Estimatif, "pdf");
    [RelayCommand] private void ExporterRecapitulatifPdf() => ExportDemande?.Invoke(TypeDocument.Recapitulatif, "pdf");
    [RelayCommand] private void ExporterBordereauCsv() => ExportDemande?.Invoke(TypeDocument.Bordereau, "csv");
    [RelayCommand] private void ExporterEstimatifExcel() => ExportDemande?.Invoke(TypeDocument.Estimatif, "xlsx");

    /// <summary>Construit le document d'état demandé à partir de l'état courant du métré.</summary>
    public DocumentMetre GenererDocument(TypeDocument type) =>
        new ReportBuilder(_calc).Construire(Metre, type);

    public void Recalculer()
    {
        var t = _calc.Calculer(Metre);
        TotalHtva = t.Htva;
        TotalTva = t.Tva;
        TotalTtc = t.Ttc;
    }
}
