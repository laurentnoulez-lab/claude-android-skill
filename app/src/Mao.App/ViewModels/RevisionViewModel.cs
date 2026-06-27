using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Mao.Data;
using Mao.Domain.Entities;

namespace Mao.App.ViewModels;

/// <summary>Révision de prix : gestion des indices et calcul d'un coefficient.</summary>
public partial class RevisionViewModel : ObservableObject
{
    private readonly RevisionService _service;

    public ObservableCollection<Indice> Indices { get; } = new();
    public ObservableCollection<FormuleReference> Formules { get; } = new();

    [ObservableProperty] private Indice? _indiceSelectionne;

    // Zone de calcul
    [ObservableProperty] private FormuleReference? _formulePourCalcul;
    [ObservableProperty] private DateTime _periodeBase = new(DateTime.Now.Year - 1, 1, 1);
    [ObservableProperty] private DateTime _periodeCourante = new(DateTime.Now.Year, DateTime.Now.Month, 1);
    [ObservableProperty] private decimal _prixBase = 100m;
    [ObservableProperty] private string _resultat = string.Empty;

    public RevisionViewModel(RevisionService service)
    {
        _service = service;
        Recharger();
    }

    private void Recharger()
    {
        Indices.Clear();
        foreach (var i in _service.ListerIndices()) Indices.Add(i);
        Formules.Clear();
        foreach (var f in _service.ListerFormules()) Formules.Add(f);
        FormulePourCalcul ??= Formules.FirstOrDefault();
    }

    [RelayCommand]
    private void AjouterIndiceSalaire() => AjouterIndice(TypeIndice.Salaire);

    [RelayCommand]
    private void AjouterIndiceMateriaux() => AjouterIndice(TypeIndice.Materiaux);

    private void AjouterIndice(TypeIndice type)
    {
        var i = _service.EnregistrerIndice(new Indice
        {
            Type = type,
            Code = type == TypeIndice.Salaire ? "S" : "M",
            Periode = new DateTime(DateTime.Now.Year, DateTime.Now.Month, 1),
            Valeur = 0m,
        });
        Indices.Add(i);
    }

    [RelayCommand]
    private void SupprimerIndice()
    {
        if (IndiceSelectionne is null) return;
        _service.SupprimerIndice(IndiceSelectionne.Id);
        Indices.Remove(IndiceSelectionne);
    }

    [RelayCommand]
    private void Enregistrer() => _service.Sauvegarder();

    [RelayCommand]
    private void CalculerRevision()
    {
        if (FormulePourCalcul is null) { Resultat = "Sélectionnez une formule."; return; }
        try
        {
            var calc = _service.CreerCalculateur();
            var coef = calc.Coefficient(FormulePourCalcul, PeriodeBase, PeriodeCourante);
            var prix = calc.PrixRevise(FormulePourCalcul, PrixBase, PeriodeBase, PeriodeCourante);
            Resultat = $"Coefficient de révision : {coef:0.#####}  →  prix révisé : {prix:0.00} €";
        }
        catch (Exception ex)
        {
            Resultat = "Erreur : " + ex.Message;
        }
    }
}
