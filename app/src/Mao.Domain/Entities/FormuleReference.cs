namespace Mao.Domain.Entities;

/// <summary>
/// Formule de révision de prix de référence (table <c>FORMULE_REFERENCE</c>).
/// Prix révisé = prix de base × coefficient de révision, où
/// coefficient = PartFixe + Σ (terme.Coefficient × indice_courant / indice_base).
/// </summary>
public class FormuleReference
{
    public int Id { get; set; }

    public string Description { get; set; } = string.Empty;

    /// <summary>Famille tarifaire associée (FREF_FAM_ID), optionnelle.</summary>
    public int? FamilleId { get; set; }

    /// <summary>Part fixe non révisable (FREF_Q_FIXE), ex. 0,20.</summary>
    public decimal PartFixe { get; set; }

    public List<FormuleTerme> Termes { get; set; } = new();

    /// <summary>Somme part fixe + coefficients ; doit valoir 1 pour une formule cohérente.</summary>
    public decimal SommeCoefficients => PartFixe + Termes.Sum(t => t.Coefficient);

    /// <summary>Vrai si la somme des coefficients vaut 1 (à 0,001 près).</summary>
    public bool EstCoherente => Math.Abs(SommeCoefficients - 1m) <= 0.001m;
}
