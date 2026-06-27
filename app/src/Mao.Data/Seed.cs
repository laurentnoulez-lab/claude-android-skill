using Mao.Domain.Entities;

namespace Mao.Data;

/// <summary>Données de référence minimales (taux de TVA belges).</summary>
public static class Seed
{
    public static void Appliquer(MaoDbContext ctx)
    {
        if (!ctx.TauxTva.Any())
        {
            ctx.TauxTva.AddRange(
                new Tva { Code = "21", Taux = 0.21m, Libelle = "TVA 21 %" },
                new Tva { Code = "6", Taux = 0.06m, Libelle = "TVA 6 %" },
                new Tva { Code = "0", Taux = 0m, Libelle = "Exonéré / 0 %" });
            ctx.SaveChanges();
        }

        if (!ctx.Utilisateurs.Any())
        {
            ctx.Utilisateurs.Add(new Utilisateur { Code = "MAO", Nom = "Administrateur", Role = "Administrateur" });
            ctx.SaveChanges();
        }

        if (!ctx.Parametres.Any())
        {
            // Paramètres repris de la section [PARAM] du mao.ini d'origine.
            ctx.Parametres.AddRange(
                new Parametre { Cle = "Liste_Norm", Valeur = "RW99", Description = "Liste normalisée active" },
                new Parametre { Cle = "nbrprix", Valeur = "5", Description = "Nombre de prix affichés" },
                new Parametre { Cle = "nbrterme", Valeur = "5", Description = "Nombre de termes d'une formule de révision" },
                new Parametre { Cle = "Intranet", Valeur = "http://routes.wallonie.be/", Description = "URL Intranet SPW" },
                new Parametre { Cle = "CCTRW99", Valeur = "http://qc.spw.wallonie.be/fr/qualiroutes/", Description = "URL du CCT Qualiroutes" });
            ctx.SaveChanges();
        }
    }
}
