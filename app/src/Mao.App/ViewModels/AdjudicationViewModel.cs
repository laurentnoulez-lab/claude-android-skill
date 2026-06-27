using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Mao.Data;
using Mao.Domain.Entities;

namespace Mao.App.ViewModels;

/// <summary>Adjudications & statistiques de prix (import du fichier statistiques MAO).</summary>
public partial class AdjudicationViewModel : ObservableObject
{
    private readonly StatistiquesService _service;

    public ObservableCollection<Adjudication> Adjudications { get; } = new();
    public ObservableCollection<StatPosteVue> Statistiques { get; } = new();

    [ObservableProperty] private string _message = string.Empty;

    /// <summary>Demande d'import : la vue choisit le fichier puis appelle <see cref="ImporterFichier"/>.</summary>
    public event Action? ImportDemande;
    /// <summary>Demande d'export : la vue choisit le fichier puis appelle <see cref="ExporterCsv"/>.</summary>
    public event Action? ExportDemande;

    public AdjudicationViewModel(StatistiquesService service)
    {
        _service = service;
        Recharger();
    }

    private void Recharger()
    {
        Adjudications.Clear();
        foreach (var a in _service.ListerAdjudications()) Adjudications.Add(a);
        Statistiques.Clear();
        foreach (var s in _service.StatistiquesParPoste()) Statistiques.Add(s);
        Message = $"{Adjudications.Count} adjudication(s), {Statistiques.Count} poste(s) avec statistiques.";
    }

    [RelayCommand] private void Importer() => ImportDemande?.Invoke();
    [RelayCommand] private void Exporter() => ExportDemande?.Invoke();

    [RelayCommand]
    private void Vider()
    {
        _service.Vider();
        Recharger();
    }

    /// <summary>Appelé par la vue après choix du fichier statistiques MAO.</summary>
    public void ImporterFichier(string chemin)
    {
        var n = _service.ImporterFichierMao(chemin);
        Recharger();
        Message = $"Import réussi : {n} ligne(s) de statistiques.";
    }

    /// <summary>Appelé par la vue après choix du fichier d'export CSV.</summary>
    public void ExporterCsv(string chemin) => _service.ExporterCsv(chemin);
}
