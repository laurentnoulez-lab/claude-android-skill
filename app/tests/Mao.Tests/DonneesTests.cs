using Mao.Data;
using Mao.Domain.Entities;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Mao.Tests;

public class BackupServiceTests
{
    private static (SqliteConnection, MaoDbContext) NouvelleBase()
    {
        var conn = new SqliteConnection("DataSource=:memory:");
        conn.Open();
        var options = new DbContextOptionsBuilder<MaoDbContext>().UseSqlite(conn).Options;
        var ctx = new MaoDbContext(options);
        ctx.Database.EnsureCreated();
        return (conn, ctx);
    }

    [Fact]
    public void Export_puis_import_conserve_les_donnees()
    {
        var fichier = Path.Combine(Path.GetTempPath(), $"backup_{Guid.NewGuid():N}.json");
        try
        {
            // Base source peuplée.
            var (c1, ctx1) = NouvelleBase();
            using (c1)
            using (ctx1)
            {
                Seed.Appliquer(ctx1);
                var svc = new MetreService(ctx1);
                var metre = svc.CreerMetre("Chantier export");
                var div = new Division { MetreId = metre.Id, Numero = 1, Intitule = "Terrassements" };
                ctx1.Divisions.Add(div); ctx1.SaveChanges();
                var chap = new Chapitre { DivisionId = div.Id, Numero = 1, Intitule = "Déblais" };
                ctx1.Chapitres.Add(chap); ctx1.SaveChanges();
                ctx1.Postes.Add(new Poste { ChapitreId = chap.Id, Numero = 1, Intitule = "Déblai", Unite = "m3", QuantitePresumee = 100m, PrixUnitaire = 5m });
                ctx1.PostesStd.Add(new PosteStd { Code = "D1000", ListeStandardisee = "QR17", Intitule = "Travaux préparatoires", Unite = "--" });
                ctx1.SaveChanges();

                new BackupService(ctx1).Exporter(fichier);
            }

            // Restauration dans une base neuve.
            var (c2, ctx2) = NouvelleBase();
            using (c2)
            using (ctx2)
            {
                new BackupService(ctx2).Importer(fichier);

                Assert.Equal(3, ctx2.TauxTva.Count());
                Assert.Single(ctx2.Metres);
                Assert.Single(ctx2.PostesStd);
                var poste = ctx2.Metres
                    .Include(m => m.Divisions).ThenInclude(d => d.Chapitres).ThenInclude(c => c.Postes)
                    .Single().Divisions.Single().Chapitres.Single().Postes.Single();
                Assert.Equal(500m, poste.MontantHtva); // hiérarchie + FK recâblées
            }
        }
        finally
        {
            if (File.Exists(fichier)) File.Delete(fichier);
        }
    }
}

public class SybaseMappingTests
{
    private static SybaseMapping.Colonne Depuis(Dictionary<string, object?> d) =>
        nom => d.TryGetValue(nom, out var v) ? v : null;

    [Fact]
    public void Map_poste_std_depuis_colonnes_sybase()
    {
        var col = Depuis(new()
        {
            ["C_POSTE_METRE_STD"] = "D1100",
            ["C_LISTE_STANDARDISEE"] = "RW99",
            ["ID_CHAPITRE_STANDARDISE"] = 1,
            ["ID_POSTE_STANDARDISE"] = 3,
            ["L_INTITULE"] = "Abattage d'arbre",
            ["C_UNITES"] = "p",
            ["O_CAUTIONNEMENT"] = "O",
            ["M_PRIX_UNITAIRE"] = "20,5000",
            ["FREF_ID"] = 76,
        });

        var p = SybaseMapping.MapPosteStd(col);

        Assert.Equal("D1100", p.Code);
        Assert.Equal(1, p.ChapitreStdId);
        Assert.Equal("Abattage d'arbre", p.Intitule);
        Assert.True(p.Cautionnement);
        Assert.Equal(20.5m, p.PrixUnitaireSuggere);
        Assert.Equal(76, p.FormuleRefId);
    }

    [Fact]
    public void Conversions_gerent_null_virgule_et_drapeaux()
    {
        var col = Depuis(new() { ["a"] = null, ["b"] = "3,14", ["c"] = "N" });
        Assert.Equal(0m, SybaseMapping.Decimale(col, "a"));
        Assert.Equal(3.14m, SybaseMapping.Decimale(col, "b"));
        Assert.Null(SybaseMapping.DecimaleNull(col, "a"));
        Assert.False(SybaseMapping.Booleen(col, "c"));
        Assert.Equal("RW99", SybaseMapping.MapMetre(Depuis(new() { ["L_INTITULE"] = "X" })).ListeNormalisee);
    }
}
