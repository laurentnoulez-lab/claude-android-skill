namespace Mao.Domain.Entities;

/// <summary>
/// Terme variable d'une formule de révision : coefficient × (indice courant / indice de base).
/// Une formule comporte une part fixe (cf. <see cref="FormuleReference.PartFixe"/>)
/// et jusqu'à plusieurs termes (param <c>nbrterme</c> du mao.ini d'origine).
/// </summary>
public class FormuleTerme
{
    public int Id { get; set; }

    public int FormuleRefId { get; set; }
    public FormuleReference? FormuleReference { get; set; }

    public TypeIndice TypeIndice { get; set; }

    /// <summary>Code de l'indice utilisé pour ce terme.</summary>
    public string CodeIndice { get; set; } = string.Empty;

    /// <summary>Coefficient du terme (ex. 0,40).</summary>
    public decimal Coefficient { get; set; }
}
