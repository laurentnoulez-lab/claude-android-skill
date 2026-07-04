using Mao.Domain.Entities;
using Mao.Domain.Services;

namespace Mao.Tests;

public class DechetGeneratorTests
{
    // Référentiel réel (extrait de CODE_DECHET de MAO.db) :
    // type 8 « Enrobé en morceau » → D9310 à 50 % ; type 5 « Béton non armé » → D9321 à 90 %.
    private static readonly List<CodeDechet> Codes = new()
    {
        new CodeDechet { Code = "D9310", TypeDechetId = 8, Pourcentage = 50m },
        new CodeDechet { Code = "D9321", TypeDechetId = 5, Pourcentage = 90m },
        new CodeDechet { Code = "D9310", TypeDechetId = 15, Pourcentage = 0m }, // 0 % → ignoré
    };

    private static readonly Dictionary<string, PosteStd> Catalogue = new()
    {
        ["D9310"] = new PosteStd { Code = "D9310", Intitule = "Mise en CTA d'enrobé bitumineux en morceaux", Unite = "t", TypePrix = "QP" },
        ["D9321"] = new PosteStd { Code = "D9321", Intitule = "Mise en CTA de béton non armé", Unite = "t", TypePrix = "QP" },
    };

    private static Metre MetreAvecDechets()
    {
        var metre = new Metre { Intitule = "Chantier", TvaIdentique = true, TauxTvaCode = "1" };
        var div = new Division { Numero = 1, Intitule = "Travaux" };
        var chap = new Chapitre { Numero = 1, Intitule = "Démolitions" };
        // 100 m² de démolition d'enrobé, coef 0,25 t/m² → 25 t de type 8
        chap.Postes.Add(new Poste { Numero = 1, Intitule = "Démolition revêtement", Unite = "m2", QuantitePresumee = 100m, PrixUnitaire = 10m, TypeDechetId = 8, CoefConversionDechet = 0.25m });
        // 40 m³ de démolition béton, coef 2,4 t/m³ → 96 t de type 5
        chap.Postes.Add(new Poste { Numero = 2, Intitule = "Démolition béton", Unite = "m3", QuantitePresumee = 40m, PrixUnitaire = 20m, TypeDechetId = 5, CoefConversionDechet = 2.4m });
        // poste sans déchet
        chap.Postes.Add(new Poste { Numero = 3, Intitule = "Signalisation", Unite = "p", QuantitePresumee = 2m, PrixUnitaire = 100m });
        div.Chapitres.Add(chap);
        metre.Divisions.Add(div);
        metre.PrixDechets.Add(new PrixPosteDechet { CodePosteStd = "D9310", Prix = 12.5m });
        return metre;
    }

    [Fact]
    public void Genere_les_postes_D9_avec_quantites_et_pourcentages()
    {
        var metre = MetreAvecDechets();
        var rapport = new DechetGenerator(Codes, Catalogue).Generer(metre);

        Assert.Equal(2, rapport.PostesGeneres);
        var chapGen = metre.Divisions.SelectMany(d => d.Chapitres)
            .Single(c => c.Intitule == DechetGenerator.IntituleChapitre);

        var d9310 = chapGen.Postes.Single(p => p.CodePosteStd == "D9310");
        Assert.Equal(12.5m, d9310.QuantitePresumee);   // 100 × 0,25 × 50 %
        Assert.Equal(12.5m, d9310.PrixUnitaire);        // prix du métré (PRIX_POSTE_DECHET)
        Assert.True(d9310.EstGenere);
        Assert.Equal("t", d9310.Unite);                 // unité du catalogue

        var d9321 = chapGen.Postes.Single(p => p.CodePosteStd == "D9321");
        Assert.Equal(86.4m, d9321.QuantitePresumee);   // 40 × 2,4 × 90 %
    }

    [Fact]
    public void Regeneration_remplace_les_postes_generes_sans_doublon()
    {
        var metre = MetreAvecDechets();
        var gen = new DechetGenerator(Codes, Catalogue);
        gen.Generer(metre);
        gen.Generer(metre); // deuxième exécution

        var generes = metre.Divisions.SelectMany(d => d.Chapitres)
            .SelectMany(c => c.Postes).Where(p => p.EstGenere).ToList();
        Assert.Equal(2, generes.Count); // pas de doublon
    }

    [Fact]
    public void Postes_generes_exclus_du_calcul_des_quantites_source()
    {
        var metre = MetreAvecDechets();
        var gen = new DechetGenerator(Codes, Catalogue);
        gen.Generer(metre);
        // marquer un généré avec un type déchet ne doit pas le compter comme source
        var rapport = gen.Generer(metre);
        Assert.Equal(2, rapport.PostesSources);
    }

    [Fact]
    public void Metre_sans_dechets_ne_genere_rien()
    {
        var metre = new Metre { Intitule = "Vide" };
        var rapport = new DechetGenerator(Codes, Catalogue).Generer(metre);
        Assert.Equal(0, rapport.PostesGeneres);
    }
}
