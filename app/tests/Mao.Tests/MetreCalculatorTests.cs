using Mao.Domain.Entities;
using Mao.Domain.Services;

namespace Mao.Tests;

public class MetreCalculatorTests
{
    private static readonly Dictionary<string, decimal> Taux = new()
    {
        ["21"] = 0.21m,
        ["6"] = 0.06m,
        ["0"] = 0m,
    };

    private static MetreCalculator Calc() => new(Taux);

    [Fact]
    public void MontantPoste_HTVA_est_quantite_fois_prix()
    {
        var poste = new Poste { QuantitePresumee = 12.5m, PrixUnitaire = 4m };
        Assert.Equal(50m, poste.MontantHtva);
    }

    [Fact]
    public void Poste_avec_tva_unique_du_metre()
    {
        var metre = new Metre { TvaIdentique = true, TauxTvaCode = "21" };
        var poste = new Poste { QuantitePresumee = 100m, PrixUnitaire = 10m };

        var t = Calc().Calculer(metre, poste);

        Assert.Equal(1000m, t.Htva);
        Assert.Equal(210m, t.Tva);
        Assert.Equal(1210m, t.Ttc);
    }

    [Fact]
    public void Poste_utilise_son_propre_taux_si_metre_non_uniforme()
    {
        var metre = new Metre { TvaIdentique = false, TauxTvaCode = "21" };
        var poste = new Poste { QuantitePresumee = 50m, PrixUnitaire = 2m, TauxTvaCode = "6" };

        var t = Calc().Calculer(metre, poste);

        Assert.Equal(100m, t.Htva);
        Assert.Equal(6m, t.Tva); // 100 * 0.06
    }

    [Fact]
    public void Total_metre_agrege_divisions_chapitres_postes()
    {
        var metre = new Metre { TvaIdentique = true, TauxTvaCode = "21" };
        var div = new Division { Metre = metre };
        var chap = new Chapitre { Division = div };
        chap.Postes.Add(new Poste { QuantitePresumee = 10m, PrixUnitaire = 5m });   // 50
        chap.Postes.Add(new Poste { QuantitePresumee = 4m, PrixUnitaire = 25m });   // 100
        div.Chapitres.Add(chap);
        metre.Divisions.Add(div);

        var t = Calc().Calculer(metre);

        Assert.Equal(150m, t.Htva);
        Assert.Equal(31.50m, t.Tva);   // 150 * 0.21
        Assert.Equal(181.50m, t.Ttc);
    }

    [Fact]
    public void Tva_arrondie_au_centime()
    {
        var metre = new Metre { TvaIdentique = true, TauxTvaCode = "21" };
        var poste = new Poste { QuantitePresumee = 1m, PrixUnitaire = 3.33m }; // 3.33 * 0.21 = 0.6993

        var t = Calc().Calculer(metre, poste);

        Assert.Equal(0.70m, t.Tva);
    }

    [Fact]
    public void Code_tva_inconnu_donne_zero()
    {
        var metre = new Metre { TvaIdentique = true, TauxTvaCode = "INEXISTANT" };
        var poste = new Poste { QuantitePresumee = 10m, PrixUnitaire = 10m };

        var t = Calc().Calculer(metre, poste);

        Assert.Equal(0m, t.Tva);
    }
}
