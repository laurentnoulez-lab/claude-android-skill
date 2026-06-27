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
    }
}
