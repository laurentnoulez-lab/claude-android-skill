using Mao.Domain.Entities;
using Mao.Domain.Services;
using Mao.Reports;

namespace Mao.Tests;

public class ReportTests
{
    private static readonly Dictionary<string, decimal> Taux = new() { ["21"] = 0.21m };

    private static Metre MetreExemple()
    {
        var metre = new Metre { Intitule = "Chantier N4", TvaIdentique = true, TauxTvaCode = "21" };
        var div = new Division { Numero = 1, Intitule = "Terrassements", Metre = metre };
        var chap = new Chapitre { Numero = 1, Intitule = "Déblais", Division = div };
        chap.Postes.Add(new Poste { Numero = 1, CodePosteStd = "D1000", Intitule = "Déblais généraux", Unite = "m³", QuantitePresumee = 100m, PrixUnitaire = 5m });
        chap.Postes.Add(new Poste { Numero = 2, Intitule = "Déblais localisés", Unite = "m³", QuantitePresumee = 10m, PrixUnitaire = 8m });
        div.Chapitres.Add(chap);
        metre.Divisions.Add(div);
        return metre;
    }

    private static ReportBuilder Builder() => new(new MetreCalculator(Taux));

    [Fact]
    public void Construit_la_hierarchie_et_les_totaux()
    {
        var doc = Builder().Construire(MetreExemple(), TypeDocument.Bordereau);

        Assert.Equal("Bordereau", doc.Titre);
        var chap = doc.Divisions.Single().Chapitres.Single();
        Assert.Equal(2, chap.Postes.Count);
        Assert.Equal(580m, doc.TotalHtva);        // 500 + 80
        Assert.Equal(121.80m, doc.TotalTva);      // 580 * 0.21
        Assert.Equal(701.80m, doc.TotalTtc);
    }

    [Fact]
    public void Csv_contient_entete_lignes_et_totaux()
    {
        var doc = Builder().Construire(MetreExemple(), TypeDocument.Estimatif);
        var csv = CsvExporter.GenererTexte(doc);

        Assert.Contains("Division;Chapitre;N°;Code;Intitulé", csv);
        Assert.Contains("D1000", csv);
        Assert.Contains("TOTAL TTC", csv);
        Assert.Contains("701,80", csv); // format fr-FR
    }

    [Fact]
    public void Pdf_genere_un_fichier_non_vide()
    {
        var doc = Builder().Construire(MetreExemple(), TypeDocument.Recapitulatif);
        var octets = PdfExporter.GenererOctets(doc);

        Assert.True(octets.Length > 1000);
        // En-tête de fichier PDF « %PDF »
        Assert.Equal(new byte[] { 0x25, 0x50, 0x44, 0x46 }, octets.Take(4).ToArray());
    }
}
