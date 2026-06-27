using Mao.Domain.Entities;

namespace Mao.Data;

/// <summary>
/// Échantillon représentatif du catalogue normalisé RW99/Qualiroutes,
/// pour rendre le module catalogue utilisable avant l'import du catalogue
/// complet (cf. <see cref="CatalogueImporter"/>). Les codes/chapitres suivent
/// la nomenclature Qualiroutes (D = terrassements, E = sous-fondations/coffres,
/// F = revêtements, etc.). À remplacer par l'import des données réelles.
/// </summary>
public static class CatalogueSeed
{
    public static void Appliquer(MaoDbContext ctx)
    {
        if (ctx.PostesStd.Any()) return;

        ctx.PostesStd.AddRange(
            P("D1000", 1, "Déblais généraux", "m³", "Déblais généraux de toute nature, y compris évacuation"),
            P("D1100", 1, "Déblais localisés", "m³", "Déblais localisés exécutés à la main ou mécaniquement"),
            P("D8110", 1, "Démolition de revêtement hydrocarboné", "m²", "Démolition de revêtement hydrocarboné existant"),
            P("E1000", 2, "Sous-fondation", "m³", "Sous-fondation en empierrement, type II"),
            P("E2000", 2, "Fondation en empierrement", "m³", "Fondation en empierrement continu"),
            P("F1000", 3, "Revêtement hydrocarboné", "t", "Revêtement en enrobé hydrocarboné, couche de liaison"),
            P("F2000", 3, "Revêtement en béton de ciment", "m²", "Revêtement en béton de ciment, épaisseur 20 cm"),
            P("G1000", 4, "Élément linéaire en béton", "m", "Bordure-filet en béton coulé en place"),
            P("G2000", 4, "Filet d'eau", "m", "Filet d'eau en pavés de béton"),
            P("C3000", 5, "Tuyau en béton", "m", "Canalisation en tuyaux de béton, DN 400"),
            P("C4000", 5, "Chambre de visite", "pièce", "Chambre de visite en béton préfabriqué"),
            P("M4340", 6, "Interface antifissure", "m²", "Interface antifissure bitumineuse avec géogrilles (classe D)"),
            P("M4350", 6, "Interface antifissure", "m²", "Interface antifissure bitumineuse avec géogrilles (classe E)"),
            P("O5334", 7, "Taille verticale d'arbustes", "m", "Taille verticale d'arbustes et de rosiers, 3,00 < H <= 4,50 m"),
            P("S1410", 8, "Marquage axial", "m", "Marquage routier axial à la peinture"),
            P("S7200", 8, "Microbilles", "kg", "Microbilles de prémélange et de saupoudrage lors de la mise en oeuvre"),
            P("H5394", 9, "Signalisation verticale", "pièce", "Panneau de signalisation, embase et plaque de base 370 x 220 x 25 mm"),
            P("K3517", 9, "Glissière de sécurité", "m", "Glissière de sécurité semi-rigide")
        );
        ctx.SaveChanges();
    }

    private static PosteStd P(string code, int chapStd, string intitule, string unite, string description) => new()
    {
        Code = code,
        ListeStandardisee = "RW99",
        ChapitreStdId = chapStd,
        Intitule = intitule,
        Unite = unite,
        Description = description,
        TypePrix = "QP",
        CoefConvMin = 1m,
        CoefConvMax = 1m,
        CoefConvPropose = 1m,
    };
}
