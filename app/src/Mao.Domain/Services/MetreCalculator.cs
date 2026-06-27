using Mao.Domain.Entities;

namespace Mao.Domain.Services;

/// <summary>Totaux calculés d'un niveau de la hiérarchie du métré.</summary>
public readonly record struct Totaux(decimal Htva, decimal Tva)
{
    public decimal Ttc => Htva + Tva;
    public static Totaux Zero => new(0m, 0m);
    public static Totaux operator +(Totaux a, Totaux b) => new(a.Htva + b.Htva, a.Tva + b.Tva);
}

/// <summary>
/// Reproduit les calculs de référence de MAO V8
/// (cf. docs/02-modele-donnees.md § « Calculs de référence »).
/// Sans dépendance UI/DB : testable en isolation.
/// </summary>
public class MetreCalculator
{
    private readonly IReadOnlyDictionary<string, decimal> _tauxParCode;

    /// <param name="tauxTva">Table code de taux → taux fractionnaire (ex. « 21 » → 0.21).</param>
    public MetreCalculator(IReadOnlyDictionary<string, decimal> tauxTva)
    {
        _tauxParCode = tauxTva ?? throw new ArgumentNullException(nameof(tauxTva));
    }

    /// <summary>Taux de TVA effectif d'un poste, en tenant compte de la TVA unique du métré.</summary>
    public decimal TauxEffectif(Metre metre, Poste poste)
    {
        var code = metre.TvaIdentique ? metre.TauxTvaCode : poste.TauxTvaCode;
        if (code is null) return 0m;
        return _tauxParCode.TryGetValue(code, out var taux) ? taux : 0m;
    }

    public Totaux Calculer(Metre metre, Poste poste)
    {
        var htva = poste.MontantHtva;
        var tva = Math.Round(htva * TauxEffectif(metre, poste), 2, MidpointRounding.AwayFromZero);
        return new Totaux(htva, tva);
    }

    public Totaux Calculer(Metre metre, Chapitre chapitre) =>
        chapitre.Postes.Aggregate(Totaux.Zero, (acc, p) => acc + Calculer(metre, p));

    public Totaux Calculer(Metre metre, Division division) =>
        division.Chapitres.Aggregate(Totaux.Zero, (acc, c) => acc + Calculer(metre, c));

    public Totaux Calculer(Metre metre) =>
        metre.Divisions.Aggregate(Totaux.Zero, (acc, d) => acc + Calculer(metre, d));
}
