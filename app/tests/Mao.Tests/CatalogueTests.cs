using Mao.Data;
using Mao.Domain.Entities;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Mao.Tests;

public class CatalogueTests : IDisposable
{
    private readonly SqliteConnection _conn;
    private readonly MaoDbContext _ctx;

    public CatalogueTests()
    {
        _conn = new SqliteConnection("DataSource=:memory:");
        _conn.Open();
        var options = new DbContextOptionsBuilder<MaoDbContext>().UseSqlite(_conn).Options;
        _ctx = new MaoDbContext(options);
        _ctx.Database.EnsureCreated();
        // Jeu de données fixe (indépendant du catalogue réel embarqué) pour des tests déterministes.
        _ctx.PostesStd.AddRange(
            new PosteStd { Code = "D1000", ListeStandardisee = "QR17", Intitule = "Travaux préparatoires", Unite = "--" },
            new PosteStd { Code = "F1000", ListeStandardisee = "QR17", Intitule = "Revêtement hydrocarboné", Unite = "t", Description = "Revêtement en enrobé" },
            new PosteStd { Code = "E2000", ListeStandardisee = "QR17", Intitule = "Fondation en empierrement", Unite = "m3" });
        _ctx.SaveChanges();
    }

    [Fact]
    public void Recherche_par_code()
    {
        var svc = new CatalogueService(_ctx);
        var r = svc.Rechercher("D1000");
        Assert.Contains(r, p => p.Code == "D1000");
    }

    [Fact]
    public void Recherche_par_mot_dans_intitule_insensible_casse()
    {
        var svc = new CatalogueService(_ctx);
        var r = svc.Rechercher("REVÊTEMENT");
        Assert.Contains(r, p => p.Code == "F1000");
        Assert.All(r, p => Assert.Contains("vêtement", (p.Intitule + p.Description).ToLower()));
    }

    [Fact]
    public void Recherche_vide_retourne_tout_trie_par_code()
    {
        var svc = new CatalogueService(_ctx);
        var r = svc.Rechercher("");
        Assert.True(r.Count >= 3);
        var codes = r.Select(p => p.Code).ToList();
        Assert.Equal(codes.OrderBy(c => c), codes);
    }

    [Fact]
    public void Import_upsert_ajoute_et_met_a_jour()
    {
        var importer = new CatalogueImporter(_ctx);
        var avant = _ctx.PostesStd.Count();

        var n = importer.Upsert(new[]
        {
            new PosteStd { Code = "Z9999", Intitule = "Poste test", Unite = "u" },     // nouveau
            new PosteStd { Code = "D1000", Intitule = "Déblais (révisé)", Unite = "m³" }, // existant
        });

        Assert.Equal(2, n);
        Assert.Equal(avant + 1, _ctx.PostesStd.Count());
        Assert.Equal("Déblais (révisé)", _ctx.PostesStd.Find("D1000")!.Intitule);
    }

    [Fact]
    public void Import_json_depuis_flux()
    {
        var importer = new CatalogueImporter(_ctx);
        var json = """[{"Code":"X1234","Intitule":"Depuis JSON","Unite":"m","ListeStandardisee":"RW99"}]""";
        using var ms = new MemoryStream(System.Text.Encoding.UTF8.GetBytes(json));

        var n = importer.Importer(ms);

        Assert.Equal(1, n);
        Assert.Equal("Depuis JSON", _ctx.PostesStd.Find("X1234")!.Intitule);
    }

    public void Dispose()
    {
        _ctx.Dispose();
        _conn.Dispose();
    }
}
