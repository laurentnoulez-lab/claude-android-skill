namespace Mao.Domain.Entities;

/// <summary>Taux de TVA (table <c>TVA</c>).</summary>
public class Tva
{
    /// <summary>Code du taux (C_TAUX_TVA), ex. « 21 », « 6 », « 0 ».</summary>
    public string Code { get; set; } = string.Empty;

    /// <summary>Taux en fraction, ex. 0.21 pour 21 %.</summary>
    public decimal Taux { get; set; }

    public string Libelle { get; set; } = string.Empty;
}
