using Mao.Data;
using Mao.Domain.Entities;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Mao.Tests;

/// <summary>Tests d'intégration sur une base SQLite en mémoire.</summary>
public class MetreServiceTests : IDisposable
{
    private readonly SqliteConnection _conn;
    private readonly MaoDbContext _ctx;

    public MetreServiceTests()
    {
        _conn = new SqliteConnection("DataSource=:memory:");
        _conn.Open();
        var options = new DbContextOptionsBuilder<MaoDbContext>()
            .UseSqlite(_conn)
            .Options;
        _ctx = new MaoDbContext(options);
        _ctx.Database.EnsureCreated();
        Seed.Appliquer(_ctx);
    }

    [Fact]
    public void Seed_cree_les_taux_tva()
    {
        Assert.Equal(3, _ctx.TauxTva.Count());
        Assert.Equal(0.21m, _ctx.TauxTva.Single(t => t.Code == "21").Taux);
    }

    [Fact]
    public void Creer_puis_lister_metre()
    {
        var svc = new MetreService(_ctx);
        svc.CreerMetre("Réfection N4");

        var liste = svc.ListerMetres();

        Assert.Single(liste);
        Assert.Equal("Réfection N4", liste[0].Intitule);
    }

    [Fact]
    public void Charger_complet_ramene_la_hierarchie()
    {
        var svc = new MetreService(_ctx);
        var metre = svc.CreerMetre("Test");
        var div = new Division { MetreId = metre.Id, Numero = 1, Intitule = "Terrassements" };
        _ctx.Divisions.Add(div);
        _ctx.SaveChanges();
        var chap = new Chapitre { DivisionId = div.Id, Numero = 1, Intitule = "Déblais" };
        _ctx.Chapitres.Add(chap);
        _ctx.SaveChanges();
        _ctx.Postes.Add(new Poste { ChapitreId = chap.Id, Numero = 1, Intitule = "Déblai général", Unite = "m³", QuantitePresumee = 1000m, PrixUnitaire = 5m });
        _ctx.SaveChanges();

        var charge = svc.ChargerComplet(metre.Id);

        Assert.NotNull(charge);
        var poste = charge!.Divisions.Single().Chapitres.Single().Postes.Single();
        Assert.Equal(5000m, poste.MontantHtva);
    }

    [Fact]
    public void Supprimer_metre_supprime_la_hierarchie()
    {
        var svc = new MetreService(_ctx);
        var metre = svc.CreerMetre("À supprimer");
        var div = new Division { MetreId = metre.Id, Numero = 1, Intitule = "D" };
        _ctx.Divisions.Add(div);
        _ctx.SaveChanges();

        svc.SupprimerMetre(metre.Id);

        Assert.Empty(svc.ListerMetres());
        Assert.Empty(_ctx.Divisions);
    }

    public void Dispose()
    {
        _ctx.Dispose();
        _conn.Dispose();
    }
}
