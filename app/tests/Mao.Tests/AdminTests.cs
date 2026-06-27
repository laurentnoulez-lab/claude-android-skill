using Mao.Data;
using Mao.Domain.Entities;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Mao.Tests;

public class AdminTests : IDisposable
{
    private readonly SqliteConnection _conn;
    private readonly MaoDbContext _ctx;
    private readonly AdminService _service;

    public AdminTests()
    {
        _conn = new SqliteConnection("DataSource=:memory:");
        _conn.Open();
        var options = new DbContextOptionsBuilder<MaoDbContext>().UseSqlite(_conn).Options;
        _ctx = new MaoDbContext(options);
        _ctx.Database.EnsureCreated();
        Seed.Appliquer(_ctx);
        _service = new AdminService(_ctx);
    }

    [Fact]
    public void Seed_cree_admin_et_parametres()
    {
        Assert.Contains(_service.ListerUtilisateurs(), u => u.Code == "MAO" && u.Role == "Administrateur");
        Assert.Equal("RW99", _service.Parametre("Liste_Norm"));
    }

    [Fact]
    public void Crud_utilisateur()
    {
        var u = _service.EnregistrerUtilisateur(new Utilisateur { Code = "JD", Nom = "Jean Dupont" });
        Assert.True(u.Id > 0);
        _service.SupprimerUtilisateur(u.Id);
        Assert.DoesNotContain(_service.ListerUtilisateurs(), x => x.Id == u.Id);
    }

    [Fact]
    public void Entite_avec_agents_cascade()
    {
        var e = _service.EnregistrerEntite(new EntiteAdmin { Code = "DG", Nom = "Direction" });
        _service.EnregistrerAgent(new AgentAdmin { Nom = "Martin", EntiteId = e.Id });
        Assert.Single(_service.ListerAgents());

        _service.SupprimerEntite(e.Id);
        Assert.Empty(_service.ListerAgents()); // agents supprimés en cascade
    }

    [Fact]
    public void Definir_parametre_met_a_jour_la_valeur()
    {
        _service.DefinirParametre("Liste_Norm", "QUALIROUTES");
        Assert.Equal("QUALIROUTES", _service.Parametre("Liste_Norm"));
    }

    [Fact]
    public void Tva_ajout_et_suppression()
    {
        _service.EnregistrerTva(new Tva { Code = "12", Taux = 0.12m, Libelle = "TVA 12 %" });
        Assert.Contains(_service.ListerTva(), t => t.Code == "12");
        _service.SupprimerTva("12");
        Assert.DoesNotContain(_service.ListerTva(), t => t.Code == "12");
    }

    public void Dispose()
    {
        _ctx.Dispose();
        _conn.Dispose();
    }
}
