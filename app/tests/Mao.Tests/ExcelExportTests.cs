using Mao.Domain.Entities;
using Mao.Domain.Services;
using Mao.Reports;

namespace Mao.Tests;

public class ExcelExportTests
{
    [Fact]
    public void Genere_un_xlsx_non_vide_et_valide()
    {
        var metre = new Metre { Intitule = "Test", TvaIdentique = true, TauxTvaCode = "21" };
        var div = new Division { Numero = 1, Intitule = "D", Metre = metre };
        var chap = new Chapitre { Numero = 1, Intitule = "C", Division = div };
        chap.Postes.Add(new Poste { Numero = 1, Intitule = "Poste", Unite = "m", QuantitePresumee = 10m, PrixUnitaire = 5m });
        div.Chapitres.Add(chap); metre.Divisions.Add(div);

        var doc = new ReportBuilder(new MetreCalculator(new Dictionary<string, decimal> { ["21"] = 0.21m }))
            .Construire(metre, TypeDocument.Estimatif);

        var octets = ExcelExporter.GenererOctets(doc);

        Assert.True(octets.Length > 1000);
        // Signature ZIP (xlsx = archive OOXML) : « PK\x03\x04 »
        Assert.Equal(new byte[] { 0x50, 0x4B, 0x03, 0x04 }, octets.Take(4).ToArray());
    }
}
