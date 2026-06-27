using Mao.Data;
using Mao.Domain.Entities;
using Mao.Domain.Services;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Mao.Tests;

public class StatistiquesParserTests
{
    private static readonly string[] Echantillon =
    {
        "1.00",
        "LOC",
        "2",
        "1\t23-149\tATTERT-2-PERTUIS\t01-03-26 00:00:00\t113068,0000",
        "2\t24.084\tWallerode\t01-03-26 00:00:00\t109636,0000",
        "3",
        "RW99\t1\t4\t1\t1\t2,000\t131,4800\t161,2500",
        "RW99\t1\t4\t1\t2\t4,000\t97,0000\t150,0000",
        "RW99\t1\t9\t7\t1\t10,000\t59,2000\t75,0000",
    };

    [Fact]
    public void Analyse_entete_adjudications_et_stats()
    {
        var f = StatistiquesMaoParser.Analyser(Echantillon);

        Assert.Equal("1.00", f.Version);
        Assert.Equal("LOC", f.Portee);
        Assert.Equal(2, f.Adjudications.Count);
        Assert.Equal(3, f.Statistiques.Count);

        var a1 = f.Adjudications[0];
        Assert.Equal("23-149", a1.Reference);
        Assert.Equal("ATTERT-2-PERTUIS", a1.Intitule);
        Assert.Equal(new DateTime(2026, 3, 1), a1.DateAdjudication);
        Assert.Equal(113068m, a1.Montant);

        var s1 = f.Statistiques[0];
        Assert.Equal("RW99", s1.Liste);
        Assert.Equal(1, s1.ChapitreStdId);
        Assert.Equal(4, s1.PosteStdId);
        Assert.Equal(131.48m, s1.PrixMin);
        Assert.Equal(161.25m, s1.PrixMax);
    }
}

/// <summary>Import de bout en bout avec le vrai fichier statistiques fourni.</summary>
public class StatistiquesServiceTests : IDisposable
{
    private readonly SqliteConnection _conn;
    private readonly MaoDbContext _ctx;
    private readonly StatistiquesService _service;

    public StatistiquesServiceTests()
    {
        _conn = new SqliteConnection("DataSource=:memory:");
        _conn.Open();
        var options = new DbContextOptionsBuilder<MaoDbContext>().UseSqlite(_conn).Options;
        _ctx = new MaoDbContext(options);
        _ctx.Database.EnsureCreated();
        _service = new StatistiquesService(_ctx);
    }

    private static string FichierReel =>
        Path.Combine(AppContext.BaseDirectory, "Data", "stats_mao.txt");

    [Fact]
    public void Import_du_vrai_fichier_mao()
    {
        Assert.True(File.Exists(FichierReel), "fixture stats_mao.txt manquante");

        var n = _service.ImporterFichierMao(FichierReel);

        Assert.True(n > 3000, $"attendu > 3000 lignes, obtenu {n}");
        Assert.Equal(19, _service.ListerAdjudications().Count);

        var stats = _service.StatistiquesParPoste();
        Assert.NotEmpty(stats);
        // Tous les prix max sont >= prix min après agrégation.
        Assert.All(stats, s => Assert.True(s.PrixMax >= s.PrixMin));
    }

    [Fact]
    public void Reimport_remplace_les_donnees()
    {
        _service.ImporterFichierMao(FichierReel);
        var apres1 = _service.ListerAdjudications().Count;
        _service.ImporterFichierMao(FichierReel);
        var apres2 = _service.ListerAdjudications().Count;
        Assert.Equal(apres1, apres2); // pas d'accumulation
    }

    [Fact]
    public void Resolution_code_via_catalogue()
    {
        // Catalogue avec un poste dont (ChapitreStdId, PosteStdId) = (1,4)
        _ctx.PostesStd.Add(new PosteStd { Code = "D1100", ListeStandardisee = "QR17", ChapitreStdId = 1, PosteStdId = 4, Intitule = "Abattage", Unite = "p" });
        _ctx.SaveChanges();

        _service.ImporterFichierMao(FichierReel);

        var resolu = _service.StatistiquesParPoste().FirstOrDefault(s => s.Code == "D1100");
        Assert.NotNull(resolu.Code);
        Assert.Equal("Abattage", resolu.Intitule);

        var fourchette = _service.FourchettePrix("D1100");
        Assert.NotNull(fourchette);
    }

    public void Dispose()
    {
        _ctx.Dispose();
        _conn.Dispose();
    }
}
