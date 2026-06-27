namespace Mao.Domain.Entities;

/// <summary>
/// En-tête d'adjudication d'un marché (table <c>ADJUDICATION</c>), tel
/// qu'importé depuis le fichier statistiques MAO (type LOC/REG/GLOB).
/// </summary>
public class Adjudication
{
    public int Id { get; set; }

    /// <summary>Numéro d'ordre dans le fichier statistiques importé.</summary>
    public int Numero { get; set; }

    /// <summary>Référence du marché.</summary>
    public string Reference { get; set; } = string.Empty;
    public string Intitule { get; set; } = string.Empty;
    public DateTime DateAdjudication { get; set; }

    /// <summary>Montant total adjugé (déclaré dans le fichier).</summary>
    public decimal Montant { get; set; }

    /// <summary>Portée des statistiques : « LOC » (locale), « REG », « GLOB ».</summary>
    public string Portee { get; set; } = "LOC";
}

/// <summary>
/// Statistique de prix d'un poste, agrégée (min/max) sur les adjudications.
/// Importée depuis le fichier statistiques MAO. Reliée au catalogue par
/// (<see cref="ChapitreStdId"/>, <see cref="PosteStdId"/>).
/// </summary>
public class StatistiquePrix
{
    public int Id { get; set; }

    /// <summary>Liste de référence du poste (ex. « RW99 »).</summary>
    public string Liste { get; set; } = string.Empty;

    public int ChapitreStdId { get; set; }
    public int PosteStdId { get; set; }

    /// <summary>Variante / info-poste (4e colonne du fichier).</summary>
    public int Variante { get; set; }

    /// <summary>Numéro de cas statistique (5e colonne).</summary>
    public int NumeroCas { get; set; }

    public decimal Quantite { get; set; }
    public decimal PrixMin { get; set; }
    public decimal PrixMax { get; set; }

    /// <summary>Code du poste normalisé résolu via le catalogue, si trouvé.</summary>
    public string? CodePosteStd { get; set; }
}
