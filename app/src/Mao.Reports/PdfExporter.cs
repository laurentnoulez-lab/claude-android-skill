using System.Globalization;
using QuestPDF.Fluent;
using QuestPDF.Helpers;
using QuestPDF.Infrastructure;

namespace Mao.Reports;

/// <summary>Exporte un <see cref="DocumentMetre"/> en PDF (QuestPDF, licence Community).</summary>
public static class PdfExporter
{
    private static readonly CultureInfo Ci = CultureInfo.GetCultureInfo("fr-FR");

    static PdfExporter() => QuestPDF.Settings.License = LicenseType.Community;

    public static void Exporter(DocumentMetre doc, string chemin)
        => Construire(doc).GeneratePdf(chemin);

    public static byte[] GenererOctets(DocumentMetre doc)
        => Construire(doc).GeneratePdf();

    private static Document Construire(DocumentMetre doc) => Document.Create(c =>
    {
        c.Page(page =>
        {
            page.Margin(30);
            page.Size(PageSizes.A4);
            page.DefaultTextStyle(t => t.FontSize(9));

            page.Header().Column(col =>
            {
                col.Item().Text(doc.Titre).FontSize(16).Bold();
                col.Item().Text(doc.Intitule).FontSize(12);
                col.Item().Text($"Liste {doc.ListeNormalisee} — édité le {doc.DateEdition.ToString("d", Ci)}")
                    .FontSize(8).FontColor(Colors.Grey.Darken1);
            });

            page.Content().PaddingVertical(8).Column(col =>
            {
                var recap = doc.Type == TypeDocument.Recapitulatif;
                foreach (var div in doc.Divisions)
                {
                    col.Item().PaddingTop(6).Text(div.Intitule).Bold().FontSize(11);
                    foreach (var chap in div.Chapitres)
                    {
                        col.Item().PaddingTop(3).Text(chap.Intitule).Bold();
                        if (!recap)
                            col.Item().Element(e => TablePostes(e, chap));
                        col.Item().AlignRight().Text(
                            $"Sous-total {chap.Intitule} : {Money(chap.Htva)} HTVA").FontSize(8);
                    }
                    col.Item().AlignRight().Text(
                        $"Total division {div.Intitule} : {Money(div.Htva)} HTVA").SemiBold();
                }

                col.Item().PaddingTop(12).LineHorizontal(1);
                col.Item().AlignRight().Text($"TOTAL HTVA : {Money(doc.TotalHtva)}").Bold();
                col.Item().AlignRight().Text($"TVA : {Money(doc.TotalTva)}");
                col.Item().AlignRight().Text($"TOTAL TTC : {Money(doc.TotalTtc)}").Bold().FontSize(12);
            });

            page.Footer().AlignCenter().Text(t =>
            {
                t.Span("MAO Moderne — ");
                t.CurrentPageNumber();
                t.Span(" / ");
                t.TotalPages();
            });
        });
    });

    private static void TablePostes(IContainer container, SectionChapitre chap) =>
        container.Table(table =>
        {
            table.ColumnsDefinition(c =>
            {
                c.ConstantColumn(50);   // code
                c.RelativeColumn(3);    // intitulé
                c.ConstantColumn(35);   // unité
                c.ConstantColumn(55);   // quantité
                c.ConstantColumn(60);   // PU
                c.ConstantColumn(70);   // montant
            });

            table.Header(h =>
            {
                Cell(h.Cell(), "Code", true); Cell(h.Cell(), "Intitulé", true); Cell(h.Cell(), "Unité", true);
                CellR(h.Cell(), "Qté", true); CellR(h.Cell(), "P.U.", true); CellR(h.Cell(), "Montant", true);
            });

            foreach (var p in chap.Postes)
            {
                Cell(table.Cell(), p.Code ?? "");
                Cell(table.Cell(), p.Intitule);
                Cell(table.Cell(), p.Unite);
                CellR(table.Cell(), p.Quantite.ToString("0.00", Ci));
                CellR(table.Cell(), p.PrixUnitaire.ToString("0.00", Ci));
                CellR(table.Cell(), Money(p.MontantHtva));
            }
        });

    private static void Cell(IContainer c, string txt, bool head = false)
    {
        var x = c.PaddingVertical(2).PaddingHorizontal(2);
        if (head) x.Text(txt).Bold().FontSize(8);
        else x.Text(txt).FontSize(8);
    }

    private static void CellR(IContainer c, string txt, bool head = false)
    {
        var x = c.PaddingVertical(2).PaddingHorizontal(2).AlignRight();
        if (head) x.Text(txt).Bold().FontSize(8);
        else x.Text(txt).FontSize(8);
    }

    private static string Money(decimal v) => v.ToString("#,0.00", Ci) + " €";
}
