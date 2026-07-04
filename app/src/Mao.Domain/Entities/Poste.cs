namespace Mao.Domain.Entities;

/// <summary>
/// Ligne de métré : un poste de travaux (table <c>POSTE</c>).
/// Montant HTVA = QuantitePresumee × PrixUnitaire.
/// </summary>
public class Poste
{
    public int Id { get; set; }
    public int ChapitreId { get; set; }
    public Chapitre? Chapitre { get; set; }

    public int Numero { get; set; }

    /// <summary>Code du poste standardisé d'origine (C_POSTE_METRE_STD), si normalisé.</summary>
    public string? CodePosteStd { get; set; }

    public string Intitule { get; set; } = string.Empty;
    public string? Description { get; set; }

    /// <summary>Unité de mesure (m², m³, t, pièce…) — C_UNITES.</summary>
    public string Unite { get; set; } = string.Empty;

    /// <summary>Quantité présumée du marché (N_QP).</summary>
    public decimal QuantitePresumee { get; set; }

    /// <summary>Prix unitaire (N_PU).</summary>
    public decimal PrixUnitaire { get; set; }

    /// <summary>Type de prix : QP (quantité présumée), QF (forfait)… — TY_PRIX_POSTE.</summary>
    public string TypePrix { get; set; } = "QP";

    /// <summary>Code de taux de TVA propre au poste (utilisé si le métré n'impose pas une TVA unique).</summary>
    public string? TauxTvaCode { get; set; }

    /// <summary>Poste issu du catalogue normalisé (vs poste libre).</summary>
    public bool EstNormalise { get; set; }

    /// <summary>Type de déchet affecté au poste (TYPE_DECHET_POSTE.TDPO_TYDE_ID).</summary>
    public int? TypeDechetId { get; set; }

    /// <summary>Coefficient de conversion quantité → quantité de déchet (TDPO_CCONVERSION).</summary>
    public decimal? CoefConversionDechet { get; set; }

    /// <summary>Numéro local de la formule de révision du métré appliquée au poste (ID_FORMULE_REVISION).</summary>
    public int? FormuleRevisionNumero { get; set; }

    /// <summary>Poste généré automatiquement (postes déchets D9xxx — O_GEN_AUTO).</summary>
    public bool EstGenere { get; set; }

    /// <summary>Montant HTVA du poste.</summary>
    public decimal MontantHtva => QuantitePresumee * PrixUnitaire;
}
