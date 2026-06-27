namespace Mao.Domain.Entities;

/// <summary>
/// Poste du catalogue normalisé Qualiroutes/RW99 (table <c>POSTE_STD</c>).
/// Sert de modèle pour créer des postes de métré « normalisés ».
/// </summary>
public class PosteStd
{
    /// <summary>Code normalisé (C_POSTE_METRE_STD), ex. « D1000 », « S7200 ».</summary>
    public string Code { get; set; } = string.Empty;

    public string ListeStandardisee { get; set; } = "RW99";
    public int ChapitreStdId { get; set; }
    public int PosteStdId { get; set; }

    public string Intitule { get; set; } = string.Empty;
    public string? Description { get; set; }

    /// <summary>Sous-puces descriptives (L_LISTE).</summary>
    public string? Liste { get; set; }

    public string Unite { get; set; } = string.Empty;

    /// <summary>Type de prix (TY_PRIX_POSTE).</summary>
    public string TypePrix { get; set; } = "QP";

    /// <summary>Référence de formule de révision de prix (FREF_ID).</summary>
    public int? FormuleRefId { get; set; }

    public decimal CoefConvMin { get; set; }
    public decimal CoefConvMax { get; set; }
    public decimal CoefConvPropose { get; set; }

    /// <summary>Type de déchet associé (TYDE_ID).</summary>
    public int? TypeDechetId { get; set; }

    /// <summary>Supprimé dans la révision RW03 (O_SUP_RW03).</summary>
    public bool SupprimeRw03 { get; set; }

    // --- Colonnes additionnelles du Catalogue des Postes Normalisés (CPN) ---

    /// <summary>Identifiant de l'info-poste (INPO_ID).</summary>
    public int? InfoPosteId { get; set; }

    /// <summary>Référence au CCT Qualiroutes (L_REF_CCTRW99).</summary>
    public string? RefCctRw99 { get; set; }

    /// <summary>Référence au cahier spécial des charges (L_REF_CSC).</summary>
    public string? RefCsc { get; set; }

    /// <summary>Poste de cautionnement (O_CAUTIONNEMENT).</summary>
    public bool Cautionnement { get; set; }

    /// <summary>Réduction applicable (O_REDUCTION_APPLICABLE).</summary>
    public bool ReductionApplicable { get; set; }

    /// <summary>Prix unitaire suggéré (M_PRIX_UNITAIRE), s'il existe.</summary>
    public decimal? PrixUnitaireSuggere { get; set; }

    /// <summary>Code du poste/chapitre parent dans la hiérarchie du catalogue (PARENT).</summary>
    public string? ParentCode { get; set; }

    /// <summary>Code de modification RW03 (C_NMODIF_RW03).</summary>
    public string? NbModifRw03 { get; set; }

    /// <summary>Vrai s'il s'agit d'une ligne de structure (chapitre/intitulé) et non d'un poste mesurable.</summary>
    public bool EstChapitre => Unite == "--" || string.IsNullOrEmpty(Unite);
}
