namespace Mao.Domain.Entities;

/// <summary>Type de déchet Qualiroutes (table <c>TYPE_DECHET</c>).</summary>
public class TypeDechet
{
    public int Id { get; set; }
    public string Libelle { get; set; } = string.Empty;

    /// <summary>Type non normalisé (TYDE_FNONNORMALISE).</summary>
    public bool NonNormalise { get; set; }

    /// <summary>Tableau de rattachement : 1 = tonnes, 2 = m³, 3 = somme (€) — TYDE_NTABLEAU.</summary>
    public int NumeroTableau { get; set; }
}

/// <summary>
/// Code de destination D9xxx autorisé pour un type de déchet, avec pourcentage
/// de répartition par défaut (table <c>CODE_DECHET</c>). Sert à la génération
/// des postes déchets (menu « Génération des postes D9000 » de MAO V8).
/// </summary>
public class CodeDechet
{
    public int Id { get; set; }

    /// <summary>Code du poste normalisé de destination (ex. « D9310 »).</summary>
    public string Code { get; set; } = string.Empty;

    public int TypeDechetId { get; set; }

    /// <summary>Pourcentage de répartition par défaut (CODE_POURCENTAGE), 0..100.</summary>
    public decimal? Pourcentage { get; set; }

    public string? Libelle { get; set; }
}

/// <summary>Prix d'un poste déchet propre à un métré (table <c>PRIX_POSTE_DECHET</c>).</summary>
public class PrixPosteDechet
{
    public int Id { get; set; }
    public int MetreId { get; set; }
    public string CodePosteStd { get; set; } = string.Empty;
    public decimal Prix { get; set; }
}
