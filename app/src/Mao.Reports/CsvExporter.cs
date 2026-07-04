using System.Globalization;
using System.Text;

namespace Mao.Reports;

/// <summary>Exporte un <see cref="DocumentMetre"/> en CSV (séparateur « ; », compatible Excel FR).</summary>
public static class CsvExporter
{
    public static void Exporter(DocumentMetre doc, string chemin)
        => File.WriteAllText(chemin, GenererTexte(doc), new UTF8Encoding(true));

    public static string GenererTexte(DocumentMetre doc)
    {
        var ci = CultureInfo.GetCultureInfo("fr-FR");
        var sb = new StringBuilder();
        sb.AppendLine($"{doc.Titre};{E(doc.Intitule)}");
        sb.AppendLine($"Liste normalisée;{E(doc.ListeNormalisee)}");
        sb.AppendLine($"Édité le;{doc.DateEdition.ToString("d", ci)}");
        sb.AppendLine();
        sb.AppendLine("Division;Chapitre;N°;Code;Intitulé;Unité;Quantité;Prix unitaire;Montant HTVA;TVA;Montant TTC");

        foreach (var div in doc.Divisions)
            foreach (var chap in div.Chapitres)
                foreach (var p in chap.Postes)
                    sb.AppendLine(string.Join(';',
                        E(div.Intitule), E(chap.Intitule), p.Numero, E(p.Code ?? ""), E(p.Intitule),
                        E(p.Unite), N(p.Quantite, ci), N(p.PrixUnitaire, ci),
                        N(p.MontantHtva, ci), N(p.Tva, ci), N(p.MontantTtc, ci)));

        sb.AppendLine();
        sb.AppendLine($"TOTAL HTVA;;;;;;;;{N(doc.TotalHtva, ci)};;");
        sb.AppendLine($"TOTAL TVA;;;;;;;;;{N(doc.TotalTva, ci)};");
        sb.AppendLine($"TOTAL TTC;;;;;;;;;;{N(doc.TotalTtc, ci)}");
        return sb.ToString();
    }

    private static string N(decimal v, CultureInfo ci) => v.ToString("0.00", ci);

    /// <summary>Échappe un champ CSV (séparateur « ; »).</summary>
    private static string E(string s)
    {
        if (s.Contains(';') || s.Contains('"') || s.Contains('\n'))
            return '"' + s.Replace("\"", "\"\"") + '"';
        return s;
    }
}
