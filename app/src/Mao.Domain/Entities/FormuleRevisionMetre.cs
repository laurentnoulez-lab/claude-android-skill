namespace Mao.Domain.Entities;

/// <summary>
/// Formule de révision de prix propre à un métré (table <c>FORMULE_REVISION</c>).
/// Formule type des marchés publics belges :
///   p = p0 × ( A·s/S + B·i/I + C )
/// avec s/S = indices salaires (courant/base), i/I = indices matériaux, et des
/// coefficients alternatifs A1/A2/B1/B2/C2/D selon la variante de révision
/// (menus « Révision A2 / B / C2 » de MAO V8). Type « 3 » = sans révision.
/// </summary>
public class FormuleRevisionMetre
{
    public int Id { get; set; }
    public int MetreId { get; set; }

    /// <summary>Numéro local de la formule dans le métré (ID_FORMULE_REVISION) ;
    /// référencé par <see cref="Poste.FormuleRevisionNumero"/>.</summary>
    public int Numero { get; set; }

    /// <summary>Type : « 1 » = formule de révision, « 3 » = sans révision (TY_FORMULE_REVISION).</summary>
    public string Type { get; set; } = "1";

    /// <summary>Formule libre saisie par l'utilisateur (O_FORMULE_LIBRE).</summary>
    public bool FormuleLibre { get; set; }

    public string Intitule { get; set; } = string.Empty;
    public string? Description { get; set; }

    public decimal? A { get; set; }
    public decimal? A1 { get; set; }
    public decimal? A2 { get; set; }
    public decimal? B { get; set; }
    public decimal? B1 { get; set; }
    public decimal? B2 { get; set; }
    public decimal? C { get; set; }
    public decimal? C2 { get; set; }
    public decimal? D { get; set; }

    /// <summary>Utilise les rubriques TP pour le calcul (O_UTILISATION_RUBRIQUE_TP).</summary>
    public bool UtilisationRubriqueTp { get; set; }

    /// <summary>Somme des coefficients principaux (A + B + C) ; vaut 1 pour une formule cohérente.</summary>
    public decimal SommeCoefficients => (A ?? 0m) + (B ?? 0m) + (C ?? 0m);

    /// <summary>
    /// Coefficient de révision pour les rapports d'indices donnés :
    /// A·(s/S) + B·(i/I) + C. Type « 3 » (sans révision) → 1.
    /// </summary>
    public decimal Coefficient(decimal salaireCourant, decimal salaireBase,
                               decimal materiauxCourant, decimal materiauxBase)
    {
        if (Type == "3") return 1m;
        if (salaireBase == 0m || materiauxBase == 0m)
            throw new InvalidOperationException("Indice de base nul pour le calcul de révision.");
        var coef = (A ?? 0m) * (salaireCourant / salaireBase)
                 + (B ?? 0m) * (materiauxCourant / materiauxBase)
                 + (C ?? 0m);
        return Math.Round(coef, 5, MidpointRounding.AwayFromZero);
    }
}
