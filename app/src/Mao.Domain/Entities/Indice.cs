namespace Mao.Domain.Entities;

/// <summary>Nature d'un indice de révision de prix.</summary>
public enum TypeIndice
{
    /// <summary>Indice salaire (table <c>INDICE_SALAIRE</c>).</summary>
    Salaire,
    /// <summary>Indice matériaux (table <c>INDICE_MATERIAUX</c>).</summary>
    Materiaux,
}

/// <summary>
/// Valeur mensuelle d'un indice de révision (salaire ou matériaux).
/// Regroupe <c>INDICE_SALAIRE</c> et <c>INDICE_MATERIAUX</c> de MAO V8.
/// </summary>
public class Indice
{
    public int Id { get; set; }
    public TypeIndice Type { get; set; }

    /// <summary>Code de l'indice (ex. « S » pour salaire, ou un code matériau).</summary>
    public string Code { get; set; } = string.Empty;

    /// <summary>Période (1er du mois).</summary>
    public DateTime Periode { get; set; }

    public decimal Valeur { get; set; }
}
