using Mao.Data;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Mao.Tests;

/// <summary>
/// Vérifie le chargement complet des données réelles embarquées
/// (reprises de MAO.db via dbunload, parseur d'apostrophes corrigé).
/// </summary>
public class DonneesReellesTests : IDisposable
{
    private readonly SqliteConnection _conn;
    private readonly MaoDbContext _ctx;

    public DonneesReellesTests()
    {
        _conn = new SqliteConnection("DataSource=:memory:");
        _conn.Open();
        var options = new DbContextOptionsBuilder<MaoDbContext>().UseSqlite(_conn).Options;
        _ctx = new MaoDbContext(options);
        _ctx.Database.EnsureCreated();
        Seed.Appliquer(_ctx);
        CatalogueSeed.Appliquer(_ctx);
        ReferentielSeed.Appliquer(_ctx);
        DonneesSeed.Appliquer(_ctx);
    }

    [Fact]
    public void Volumes_complets_et_attachements()
    {
        Assert.Equal(701, _ctx.Metres.Count());
        Assert.Equal(78742, _ctx.Postes.Count());
        // 14 285 lignes dans POSTE_STD dont 193 doublons stricts → 14 092 codes distincts.
        Assert.Equal(14092, _ctx.PostesStd.Count());       // catalogue QR21 de la base utilisateur
        Assert.Equal(29, _ctx.TypesDechets.Count());
        Assert.Equal(93, _ctx.CodesDechets.Count());
        Assert.Equal(704, _ctx.FormulesRevisionMetre.Count());
        Assert.Equal(1269, _ctx.PrixPostesDechets.Count());
        Assert.Equal(4210, _ctx.Postes.Count(p => p.TypeDechetId != null));
        Assert.True(_ctx.Indices.Count() > 2000);           // indices salaires + matériaux réels
        Assert.Equal(109, _ctx.FormulesReference.Count());
    }

    [Fact]
    public void Apostrophes_correctement_decodees()
    {
        // « filet d'eau » contenait '' dans l'export Sybase : le poste doit
        // exister avec l'apostrophe simple et des champs non décalés.
        var poste = _ctx.Postes.First(p =>
            p.CodePosteStd == "D6323-E" && p.Description!.Contains("filet d'eau"));
        Assert.NotEqual(0m, poste.QuantitePresumee);
        Assert.True(poste.PrixUnitaire < 100000m);
    }

    [Fact]
    public void Tva_reelle_et_totaux_plausibles()
    {
        Assert.Equal(0.21m, _ctx.TauxTva.Single(t => t.Code == "1").Taux);
        // Un métré réel avec formules de révision : SPI CAHOTTES2 (dossier récent)
        var metre = new MetreService(_ctx).ListerMetres()
            .First(m => m.Intitule.Contains("CAHOTTES2_LOT2"));
        var complet = new MetreService(_ctx).ChargerComplet(metre.Id)!;
        var total = new MetreService(_ctx).CreerCalculateur().Calculer(complet);
        Assert.InRange(total.Htva, 1_000_000m, 20_000_000m); // ≈ 5,7 M€
    }

    public void Dispose()
    {
        _ctx.Dispose();
        _conn.Dispose();
    }
}
