using Mao.App.ViewModels;
using Mao.Data;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Mao.Tests;

public class MetreEditorViewModelTests : IDisposable
{
    private readonly SqliteConnection _conn;
    private readonly MaoDbContext _ctx;
    private readonly MetreService _service;

    public MetreEditorViewModelTests()
    {
        _conn = new SqliteConnection("DataSource=:memory:");
        _conn.Open();
        var options = new DbContextOptionsBuilder<MaoDbContext>().UseSqlite(_conn).Options;
        _ctx = new MaoDbContext(options);
        _ctx.Database.EnsureCreated();
        Seed.Appliquer(_ctx);
        _service = new MetreService(_ctx);
    }

    private MetreEditorViewModel NouvelEditeur()
    {
        var metre = _service.CreerMetre("Chantier test");
        var complet = _service.ChargerComplet(metre.Id)!;
        return new MetreEditorViewModel(_service, complet);
    }

    [Fact]
    public void Ajouter_poste_cree_la_structure_et_une_ligne()
    {
        var ed = NouvelEditeur();

        ed.AjouterPosteCommand.Execute(null);

        Assert.Single(ed.Lignes);
        Assert.Single(ed.Metre.Divisions);
        Assert.Single(ed.Metre.Divisions[0].Chapitres);
        Assert.Single(ed.Metre.Divisions[0].Chapitres[0].Postes);
    }

    [Fact]
    public void Modifier_quantite_et_pu_met_a_jour_les_totaux()
    {
        var ed = NouvelEditeur();
        ed.AjouterPosteCommand.Execute(null);
        var ligne = ed.Lignes[0];

        ligne.QuantitePresumee = 100m;
        ligne.PrixUnitaire = 12m;

        Assert.Equal(1200m, ed.TotalHtva);
        Assert.Equal(252m, ed.TotalTva);   // TVA 21 % par défaut
        Assert.Equal(1452m, ed.TotalTtc);
    }

    [Fact]
    public void Supprimer_poste_retire_la_ligne_et_recalcule()
    {
        var ed = NouvelEditeur();
        ed.AjouterPosteCommand.Execute(null);
        ed.Lignes[0].QuantitePresumee = 5m;
        ed.Lignes[0].PrixUnitaire = 5m;
        ed.LigneSelectionnee = ed.Lignes[0];

        ed.SupprimerPosteCommand.Execute(null);

        Assert.Empty(ed.Lignes);
        Assert.Equal(0m, ed.TotalHtva);
    }

    [Fact]
    public void Enregistrer_persiste_les_modifications()
    {
        var ed = NouvelEditeur();
        ed.AjouterPosteCommand.Execute(null);
        ed.Lignes[0].Intitule = "Déblai général";
        ed.Lignes[0].QuantitePresumee = 10m;
        ed.Lignes[0].PrixUnitaire = 3m;
        ed.EnregistrerCommand.Execute(null);

        var rechargé = _service.ChargerComplet(ed.Metre.Id)!;
        var poste = rechargé.Divisions[0].Chapitres[0].Postes[0];
        Assert.Equal("Déblai général", poste.Intitule);
        Assert.Equal(30m, poste.MontantHtva);
    }

    public void Dispose()
    {
        _ctx.Dispose();
        _conn.Dispose();
    }
}
