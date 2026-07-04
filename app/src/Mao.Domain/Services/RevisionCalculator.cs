using Mao.Domain.Entities;

namespace Mao.Domain.Services;

/// <summary>
/// Calcule les coefficients de révision de prix selon la formule type des
/// marchés publics belges (Qualiroutes) :
///   p = p0 × ( a + Σ b_k × (I_k / I0_k) )
/// où a = part fixe, b_k = coefficients des termes, I = indices (salaire/matériaux).
/// </summary>
public class RevisionCalculator
{
    /// <summary>
    /// Fournit la valeur d'un indice pour un type, un code et une période donnés.
    /// </summary>
    public delegate decimal FournisseurIndice(TypeIndice type, string code, DateTime periode);

    private readonly FournisseurIndice _indice;

    public RevisionCalculator(FournisseurIndice fournisseurIndice)
    {
        _indice = fournisseurIndice ?? throw new ArgumentNullException(nameof(fournisseurIndice));
    }

    /// <summary>Coefficient de révision entre la période de base et la période courante.</summary>
    public decimal Coefficient(FormuleReference formule, DateTime periodeBase, DateTime periodeCourante)
    {
        var coef = formule.PartFixe;
        foreach (var t in formule.Termes)
        {
            var iBase = _indice(t.TypeIndice, t.CodeIndice, periodeBase);
            if (iBase == 0m)
                throw new InvalidOperationException(
                    $"Indice de base nul pour {t.TypeIndice}/{t.CodeIndice} en {periodeBase:yyyy-MM}.");
            var iCourant = _indice(t.TypeIndice, t.CodeIndice, periodeCourante);
            coef += t.Coefficient * (iCourant / iBase);
        }
        return Math.Round(coef, 5, MidpointRounding.AwayFromZero);
    }

    /// <summary>Prix révisé = prix de base × coefficient de révision (arrondi au centime).</summary>
    public decimal PrixRevise(FormuleReference formule, decimal prixBase, DateTime periodeBase, DateTime periodeCourante)
        => Math.Round(prixBase * Coefficient(formule, periodeBase, periodeCourante), 2, MidpointRounding.AwayFromZero);
}
