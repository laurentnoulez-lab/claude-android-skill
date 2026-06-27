namespace Mao.Domain.Entities;

/// <summary>
/// Un métré : le bordereau quantitatif d'un marché de travaux.
/// Correspond à la table <c>METRE</c> de MAO V8 (clé ID_DOSSIER_ENTREPRISE).
/// </summary>
public class Metre
{
    public int Id { get; set; }

    /// <summary>Intitulé du métré (L_INTITULE).</summary>
    public string Intitule { get; set; } = string.Empty;

    /// <summary>Liste normalisée active, ex. « RW99 » / « Qualiroutes ».</summary>
    public string ListeNormalisee { get; set; } = "RW99";

    /// <summary>Cahier des charges type (C_CCT).</summary>
    public string? CodeCct { get; set; }

    /// <summary>Verrou d'édition concurrente (O_VERROU).</summary>
    public bool Verrouille { get; set; }

    /// <summary>Si vrai, le <see cref="TauxTvaCode"/> s'applique à tous les postes (O_TVA_IDENTIQUE).</summary>
    public bool TvaIdentique { get; set; }

    /// <summary>Code de taux de TVA appliqué quand <see cref="TvaIdentique"/> est vrai (C_TAUX_TVA).</summary>
    public string? TauxTvaCode { get; set; }

    public DateTime DerniereMaj { get; set; } = DateTime.Now;

    public List<Division> Divisions { get; set; } = new();
}
