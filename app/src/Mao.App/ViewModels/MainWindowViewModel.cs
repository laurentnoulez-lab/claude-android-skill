using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Mao.Data;
using Mao.Domain.Entities;

namespace Mao.App.ViewModels;

public partial class MainWindowViewModel : ObservableObject
{
    private readonly MetreService _service;

    /// <summary>Exposé pour que la vue construise le sélecteur de catalogue.</summary>
    public CatalogueService Catalogue { get; }

    /// <summary>Services exposés pour les fenêtres Administration / Révision / Statistiques.</summary>
    public AdminService Admin { get; }
    public RevisionService Revision { get; }
    public StatistiquesService Statistiques { get; }
    public DonneesService Donnees { get; }
    public DechetService Dechets { get; }

    public ObservableCollection<Metre> Metres { get; } = new();

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(OuvrirCommand))]
    [NotifyCanExecuteChangedFor(nameof(SupprimerCommand))]
    private Metre? _metreSelectionne;

    [ObservableProperty] private MetreEditorViewModel? _editeur;

    /// <summary>Intitulé saisi pour la création d'un nouveau métré.</summary>
    [ObservableProperty] private string _nouvelIntitule = string.Empty;

    public MainWindowViewModel(MetreService service, CatalogueService catalogue,
                               AdminService admin, RevisionService revision,
                               StatistiquesService statistiques, DonneesService donnees,
                               DechetService dechets)
    {
        _service = service;
        Catalogue = catalogue;
        Admin = admin;
        Revision = revision;
        Statistiques = statistiques;
        Donnees = donnees;
        Dechets = dechets;
        Rafraichir();
    }

    /// <summary>Recharge la liste des métrés et ferme l'éditeur (après un import remplaçant).</summary>
    public void RafraichirApresImport()
    {
        Editeur = null;
        Rafraichir();
    }

    private void Rafraichir()
    {
        Metres.Clear();
        foreach (var m in _service.ListerMetres())
            Metres.Add(m);
    }

    [RelayCommand]
    private void Creer()
    {
        var intitule = string.IsNullOrWhiteSpace(NouvelIntitule) ? "Nouveau métré" : NouvelIntitule.Trim();
        var metre = _service.CreerMetre(intitule);
        NouvelIntitule = string.Empty;
        Rafraichir();
        MetreSelectionne = metre;
        Ouvrir();
    }

    private bool PeutAgir() => MetreSelectionne is not null;

    [RelayCommand(CanExecute = nameof(PeutAgir))]
    private void Ouvrir()
    {
        if (MetreSelectionne is null) return;
        var complet = _service.ChargerComplet(MetreSelectionne.Id);
        if (complet is null) return;
        Editeur = new MetreEditorViewModel(_service, complet, Dechets);
    }

    [RelayCommand(CanExecute = nameof(PeutAgir))]
    private void Supprimer()
    {
        if (MetreSelectionne is null) return;
        var id = MetreSelectionne.Id;
        _service.SupprimerMetre(id);
        if (Editeur?.Metre.Id == id) Editeur = null;
        Rafraichir();
    }
}
