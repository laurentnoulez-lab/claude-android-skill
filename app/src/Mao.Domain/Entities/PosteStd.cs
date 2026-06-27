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
}
