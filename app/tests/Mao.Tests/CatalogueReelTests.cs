using Mao.Data;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Mao.Tests;

/// <summary>Vérifie le chargement du vrai catalogue Qualiroutes (CPN/QR17) embarqué.</summary>
public class CatalogueReelTests : IDisposable
{
    private readonly SqliteConnection _conn;
    private readonly MaoDbContext _ctx;

    public CatalogueReelTests()
    {
        _conn = new SqliteConnection("DataSource=:memory:");
        _conn.Open();
        var options = new DbContextOptionsBuilder<MaoDbContext>().UseSqlite(_conn).Options;
        _ctx = new MaoDbContext(options);
        _ctx.Database.EnsureCreated();
    }

    [Fact]
    public void Catalogue_embarque_charge_les_postes_qr17()
    {
        CatalogueSeed.Appliquer(_ctx);

        var total = _ctx.PostesStd.Count();
        Assert.True(total > 9000, $"catalogue attendu > 9000 postes, obtenu {total}");

        var d1100 = _ctx.PostesStd.Find("D1100");
        Assert.NotNull(d1100);
        Assert.Equal("QR17", d1100!.ListeStandardisee);
        Assert.Contains("Abattage", d1100.Intitule);
    }

    public void Dispose()
    {
        _ctx.Dispose();
        _conn.Dispose();
    }
}
