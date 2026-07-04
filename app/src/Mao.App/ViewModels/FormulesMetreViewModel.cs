using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Mao.Data;
using Mao.Domain.Entities;

namespace Mao.App.ViewModels;

/// <summary>
/// Formules de révision d'un métré (table FORMULE_REVISION reprise de MAO V8)
/// et calcul du coefficient p/p0 = A·s/S + B·i/I + C.
/// </summary>
public partial class FormulesMetreViewModel : ObservableObject
{
    public Metre Metre { get; }
    public ObservableCollection<FormuleRevisionMetre> Formules { get; } = new();

    [ObservableProperty] private FormuleRevisionMetre? _selection;

    // Valeurs d'indices pour la simulation (pré-remplies depuis les indices stockés).
    [ObservableProperty] private decimal _salaireBase = 1m;
    [ObservableProperty] private decimal _salaireCourant = 1m;
    [ObservableProperty] private decimal _materiauxBase = 1m;
    [ObservableProperty] private decimal _materiauxCourant = 1m;
    [ObservableProperty] private decimal _prixBase = 100m;
    [ObservableProperty] private string _resultat = string.Empty;

    public FormulesMetreViewModel(Metre metre, RevisionService revision)
    {
        Metre = metre;
        foreach (var f in metre.FormulesRevision.OrderBy(f => f.Numero))
            Formules.Add(f);
        Selection = Formules.FirstOrDefault();

        // Pré-remplissage : dernier et avant-dernier indices disponibles.
        PreRemplir(revision);
    }

    private void PreRemplir(RevisionService revision)
    {
        var salaires = revision.ListerIndices(TypeIndice.Salaire)
            .Where(i => i.Code == "CS_A1").OrderBy(i => i.Periode).ToList();
        if (salaires.Count >= 2)
        {
            SalaireBase = salaires[^2].Valeur;
            SalaireCourant = salaires[^1].Valeur;
        }
        var materiaux = revision.ListerIndices(TypeIndice.Materiaux)
            .Where(i => i.Code == "I").OrderBy(i => i.Periode).ToList();
        if (materiaux.Count >= 2)
        {
            MateriauxBase = materiaux[^2].Valeur;
            MateriauxCourant = materiaux[^1].Valeur;
        }
    }

    [RelayCommand]
    private void Calculer()
    {
        if (Selection is null) { Resultat = "Sélectionnez une formule."; return; }
        try
        {
            var coef = Selection.Coefficient(SalaireCourant, SalaireBase, MateriauxCourant, MateriauxBase);
            var prix = Math.Round(PrixBase * coef, 2, MidpointRounding.AwayFromZero);
            Resultat = $"Coefficient p/p0 = {coef:0.#####}  →  prix révisé : {prix:0.00} €";
        }
        catch (Exception ex)
        {
            Resultat = "Erreur : " + ex.Message;
        }
    }
}
