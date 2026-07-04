namespace Mao.Reports;

/// <summary>Type d'état imprimé (équivalents MAO V8).</summary>
public enum TypeDocument
{
    /// <summary>Bordereau : liste détaillée des postes avec quantités et prix.</summary>
    Bordereau,
    /// <summary>Métré estimatif : bordereau valorisé (montants HTVA/TVA/TTC).</summary>
    Estimatif,
    /// <summary>Métré récapitulatif : totaux par chapitre/division + TVA + TTC.</summary>
    Recapitulatif,
}

/// <summary>Ligne de poste telle qu'imprimée dans un état.</summary>
public record LignePoste(
    int Numero,
    string? Code,
    string Intitule,
    string Unite,
    decimal Quantite,
    decimal PrixUnitaire,
    decimal MontantHtva,
    decimal Tva,
    decimal MontantTtc);

/// <summary>Un chapitre dans l'état (avec ses postes et son sous-total).</summary>
public record SectionChapitre(string Intitule, IReadOnlyList<LignePoste> Postes, decimal Htva, decimal Tva, decimal Ttc);

/// <summary>Une division dans l'état (avec ses chapitres et son sous-total).</summary>
public record SectionDivision(string Intitule, IReadOnlyList<SectionChapitre> Chapitres, decimal Htva, decimal Tva, decimal Ttc);

/// <summary>Document de métré prêt à exporter (PDF/CSV).</summary>
public record DocumentMetre(
    TypeDocument Type,
    string Intitule,
    string ListeNormalisee,
    DateTime DateEdition,
    IReadOnlyList<SectionDivision> Divisions,
    decimal TotalHtva,
    decimal TotalTva,
    decimal TotalTtc)
{
    public string Titre => Type switch
    {
        TypeDocument.Bordereau => "Bordereau",
        TypeDocument.Estimatif => "Métré estimatif",
        TypeDocument.Recapitulatif => "Métré récapitulatif",
        _ => "Métré",
    };
}
