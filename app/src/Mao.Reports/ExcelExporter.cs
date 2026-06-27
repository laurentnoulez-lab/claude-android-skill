using ClosedXML.Excel;

namespace Mao.Reports;

/// <summary>Exporte un <see cref="DocumentMetre"/> en classeur Excel (.xlsx).</summary>
public static class ExcelExporter
{
    public static void Exporter(DocumentMetre doc, string chemin)
    {
        using var wb = Construire(doc);
        wb.SaveAs(chemin);
    }

    public static byte[] GenererOctets(DocumentMetre doc)
    {
        using var wb = Construire(doc);
        using var ms = new MemoryStream();
        wb.SaveAs(ms);
        return ms.ToArray();
    }

    private static XLWorkbook Construire(DocumentMetre doc)
    {
        var wb = new XLWorkbook();
        var ws = wb.AddWorksheet("Métré");

        ws.Cell(1, 1).Value = doc.Titre;
        ws.Cell(1, 1).Style.Font.Bold = true;
        ws.Cell(1, 1).Style.Font.FontSize = 14;
        ws.Cell(2, 1).Value = doc.Intitule;
        ws.Cell(3, 1).Value = $"Liste {doc.ListeNormalisee} — édité le {doc.DateEdition:d}";

        int r = 5;
        string[] entetes = { "Division", "Chapitre", "N°", "Code", "Intitulé", "Unité",
                             "Quantité", "Prix unitaire", "Montant HTVA", "TVA", "Montant TTC" };
        for (int c = 0; c < entetes.Length; c++)
        {
            var cell = ws.Cell(r, c + 1);
            cell.Value = entetes[c];
            cell.Style.Font.Bold = true;
            cell.Style.Fill.BackgroundColor = XLColor.LightGray;
        }
        r++;

        foreach (var div in doc.Divisions)
            foreach (var chap in div.Chapitres)
                foreach (var p in chap.Postes)
                {
                    ws.Cell(r, 1).Value = div.Intitule;
                    ws.Cell(r, 2).Value = chap.Intitule;
                    ws.Cell(r, 3).Value = p.Numero;
                    ws.Cell(r, 4).Value = p.Code ?? "";
                    ws.Cell(r, 5).Value = p.Intitule;
                    ws.Cell(r, 6).Value = p.Unite;
                    ws.Cell(r, 7).Value = p.Quantite;
                    ws.Cell(r, 8).Value = p.PrixUnitaire;
                    ws.Cell(r, 9).Value = p.MontantHtva;
                    ws.Cell(r, 10).Value = p.Tva;
                    ws.Cell(r, 11).Value = p.MontantTtc;
                    r++;
                }

        r++;
        ws.Cell(r, 8).Value = "TOTAL HTVA"; ws.Cell(r, 9).Value = doc.TotalHtva;
        ws.Cell(r, 8).Style.Font.Bold = true;
        r++;
        ws.Cell(r, 8).Value = "TVA"; ws.Cell(r, 10).Value = doc.TotalTva;
        r++;
        ws.Cell(r, 8).Value = "TOTAL TTC"; ws.Cell(r, 11).Value = doc.TotalTtc;
        ws.Cell(r, 8).Style.Font.Bold = true;

        foreach (var col in new[] { 7, 8, 9, 10, 11 })
            ws.Column(col).Style.NumberFormat.Format = "#,##0.00";
        ws.Columns().AdjustToContents();
        return wb;
    }
}
