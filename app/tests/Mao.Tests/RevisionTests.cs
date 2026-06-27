using Mao.Data;
using Mao.Domain.Entities;
using Mao.Domain.Services;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Mao.Tests;

public class RevisionCalculatorTests
{
    private static FormuleReference FormuleStandard()
    {
        // p = p0 ( 0,20 + 0,40·S/S0 + 0,40·I/I0 )
        var f = new FormuleReference { Description = "Standard", PartFixe = 0.20m };
        f.Termes.Add(new FormuleTerme { TypeIndice = TypeIndice.Salaire, CodeIndice = "S", Coefficient = 0.40m });
        f.Termes.Add(new FormuleTerme { TypeIndice = TypeIndice.Materiaux, CodeIndice = "I", Coefficient = 0.40m });
        return f;
    }

    [Fact]
    public void Formule_coherente_somme_a_un()
    {
        Assert.True(FormuleStandard().EstCoherente);
    }

    [Fact]
    public void Coefficient_un_quand_indices_inchanges()
    {
        var calc = new RevisionCalculator((_, _, _) => 100m);
        var coef = calc.Coefficient(FormuleStandard(), new DateTime(2020, 1, 1), new DateTime(2024, 1, 1));
        Assert.Equal(1m, coef);
    }

    [Fact]
    public void Coefficient_suit_variation_des_indices()
    {
        // Salaire +10 % (110/100), Matériaux +20 % (120/100)
        decimal Indice(TypeIndice t, string c, DateTime p)
        {
            var basePeriode = p.Year == 2020;
            if (t == TypeIndice.Salaire) return basePeriode ? 100m : 110m;
            return basePeriode ? 100m : 120m;
        }
        var calc = new RevisionCalculator(Indice);
        // 0,20 + 0,40·1,10 + 0,40·1,20 = 0,20 + 0,44 + 0,48 = 1,12
        var coef = calc.Coefficient(FormuleStandard(), new DateTime(2020, 1, 1), new DateTime(2024, 1, 1));
        Assert.Equal(1.12m, coef);

        var prix = calc.PrixRevise(FormuleStandard(), 250m, new DateTime(2020, 1, 1), new DateTime(2024, 1, 1));
        Assert.Equal(280m, prix); // 250 × 1,12
    }

    [Fact]
    public void Indice_de_base_nul_leve_une_erreur()
    {
        var calc = new RevisionCalculator((_, _, _) => 0m);
        Assert.Throws<InvalidOperationException>(() =>
            calc.Coefficient(FormuleStandard(), new DateTime(2020, 1, 1), new DateTime(2024, 1, 1)));
    }
}

public class RevisionServiceTests : IDisposable
{
    private readonly SqliteConnection _conn;
    private readonly MaoDbContext _ctx;
    private readonly RevisionService _service;

    public RevisionServiceTests()
    {
        _conn = new SqliteConnection("DataSource=:memory:");
        _conn.Open();
        var options = new DbContextOptionsBuilder<MaoDbContext>().UseSqlite(_conn).Options;
        _ctx = new MaoDbContext(options);
        _ctx.Database.EnsureCreated();
        _service = new RevisionService(_ctx);
    }

    [Fact]
    public void Indice_normalise_la_periode_au_premier_du_mois()
    {
        var i = _service.EnregistrerIndice(new Indice { Type = TypeIndice.Salaire, Code = "S", Periode = new DateTime(2024, 3, 15), Valeur = 123.4m });
        Assert.Equal(new DateTime(2024, 3, 1), i.Periode);
    }

    [Fact]
    public void Calculateur_branche_sur_indices_stockes()
    {
        _service.EnregistrerIndice(new Indice { Type = TypeIndice.Salaire, Code = "S", Periode = new DateTime(2020, 1, 1), Valeur = 100m });
        _service.EnregistrerIndice(new Indice { Type = TypeIndice.Salaire, Code = "S", Periode = new DateTime(2024, 1, 1), Valeur = 130m });

        var f = new FormuleReference { Description = "Salaire seul", PartFixe = 0.20m };
        f.Termes.Add(new FormuleTerme { TypeIndice = TypeIndice.Salaire, CodeIndice = "S", Coefficient = 0.80m });
        _service.EnregistrerFormule(f);

        var calc = _service.CreerCalculateur();
        // 0,20 + 0,80·(130/100) = 0,20 + 1,04 = 1,24
        Assert.Equal(1.24m, calc.Coefficient(f, new DateTime(2020, 1, 1), new DateTime(2024, 1, 1)));
    }

    public void Dispose()
    {
        _ctx.Dispose();
        _conn.Dispose();
    }
}
