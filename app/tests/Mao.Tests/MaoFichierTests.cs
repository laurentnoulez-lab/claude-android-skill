using Mao.Data;
using Mao.Domain.Entities;
using Mao.Domain.Services;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Mao.Tests;

public class MaoFichierTests
{
    private static Metre MetreComplet()
    {
        var metre = new Metre
        {
            Intitule = "N62 - Traversée d'essai",
            TvaIdentique = true,
            TauxTvaCode = "1",
            ListeNormalisee = "RW99",
            CodeCct = "QR21",
        };
        var div = new Division { Numero = 1, Intitule = "Voirie" };
        var chap = new Chapitre { Numero = 1, Intitule = "Démolitions" };
        chap.Postes.Add(new Poste
        {
            Numero = 1, CodePosteStd = "D6323-E",
            Intitule = "Démolition filet d'eau",
            Description = "Démolition sélective de filet d'eau, en béton préfabriqué",
            Unite = "m", QuantitePresumee = 200m, PrixUnitaire = 8.5m,
            TypePrix = "QP", TauxTvaCode = "1",
            TypeDechetId = 5, CoefConversionDechet = 0.132m,
            FormuleRevisionNumero = 3,
        });
        chap.Postes.Add(new Poste
        {
            Numero = 2, Intitule = "Poste libre ; avec point-virgule",
            Unite = "p", QuantitePresumee = 3m, PrixUnitaire = 150m, TypePrix = "QP",
        });
        div.Chapitres.Add(chap);
        metre.Divisions.Add(div);
        metre.FormulesRevision.Add(new FormuleRevisionMetre
        {
            Numero = 3, Type = "1", Intitule = "Autres postes", A = 0.43m, B = 0.11m, C = 0.46m,
        });
        metre.PrixDechets.Add(new PrixPosteDechet { CodePosteStd = "D9321", Prix = 10m });
        return metre;
    }

    [Fact]
    public void Aller_retour_ecrire_lire_conserve_tout()
    {
        var original = MetreComplet();
        var texte = MaoFichierMetre.Ecrire(original);
        var relu = MaoFichierMetre.Lire(texte.Split('\n').Select(l => l.TrimEnd('\r')));

        Assert.Equal(original.Intitule, relu.Intitule);
        Assert.Equal(original.TvaIdentique, relu.TvaIdentique);
        Assert.Equal(original.TauxTvaCode, relu.TauxTvaCode);
        Assert.Equal(original.CodeCct, relu.CodeCct);

        var posteOrig = original.Divisions[0].Chapitres[0].Postes[0];
        var posteRelu = relu.Divisions.Single().Chapitres.Single().Postes
            .Single(p => p.CodePosteStd == "D6323-E");
        Assert.Equal(posteOrig.Intitule, posteRelu.Intitule);
        Assert.Equal(posteOrig.QuantitePresumee, posteRelu.QuantitePresumee);
        Assert.Equal(posteOrig.PrixUnitaire, posteRelu.PrixUnitaire);
        Assert.Equal(posteOrig.TypeDechetId, posteRelu.TypeDechetId);
        Assert.Equal(posteOrig.CoefConversionDechet, posteRelu.CoefConversionDechet);
        Assert.Equal(posteOrig.FormuleRevisionNumero, posteRelu.FormuleRevisionNumero);

        var f = relu.FormulesRevision.Single();
        Assert.Equal(0.43m, f.A);
        Assert.Equal(0.46m, f.C);

        Assert.Equal(10m, relu.PrixDechets.Single().Prix);
    }

    [Fact]
    public void Decimales_a_la_virgule_acceptees_en_lecture()
    {
        var texte = MaoFichierMetre.Ecrire(MetreComplet()).Replace("8.5", "8,5");
        var relu = MaoFichierMetre.Lire(texte.Split('\n').Select(l => l.TrimEnd('\r')));
        var poste = relu.Divisions.Single().Chapitres.Single().Postes
            .Single(p => p.CodePosteStd == "D6323-E");
        Assert.Equal(8.5m, poste.PrixUnitaire);
    }

    [Fact]
    public void Version_inconnue_rejetee()
    {
        Assert.Throws<System.IO.InvalidDataException>(() =>
            MaoFichierMetre.Lire(new[] { "9.99", "1" }));
    }

    [Fact]
    public void Import_export_via_service_et_fichier()
    {
        using var conn = new SqliteConnection("DataSource=:memory:");
        conn.Open();
        var options = new DbContextOptionsBuilder<MaoDbContext>().UseSqlite(conn).Options;
        using var ctx = new MaoDbContext(options);
        ctx.Database.EnsureCreated();

        var svc = new MaoFichierService(ctx);
        var fichier = Path.Combine(Path.GetTempPath(), $"metre_{Guid.NewGuid():N}.mao");
        try
        {
            svc.Exporter(MetreComplet(), fichier);
            var importe = svc.Importer(fichier);

            Assert.True(importe.Id > 0);
            var recharge = new MetreService(ctx).ChargerComplet(importe.Id)!;
            Assert.Equal("N62 - Traversée d'essai", recharge.Intitule);
            Assert.Equal(2, recharge.Divisions.Single().Chapitres.Single().Postes.Count);
            Assert.Single(recharge.FormulesRevision);
            Assert.Single(recharge.PrixDechets);
        }
        finally
        {
            if (File.Exists(fichier)) File.Delete(fichier);
        }
    }
}
