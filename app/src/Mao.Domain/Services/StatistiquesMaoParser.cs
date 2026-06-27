using System.Globalization;
using Mao.Domain.Entities;

namespace Mao.Domain.Services;

/// <summary>Contenu décodé d'un fichier statistiques MAO.</summary>
public record FichierStatistiques(
    string Version,
    string Portee,
    IReadOnlyList<Adjudication> Adjudications,
    IReadOnlyList<StatistiquePrix> Statistiques);

/// <summary>
/// Analyse le format natif du fichier statistiques de MAO V8 :
/// <code>
/// ligne 1 : version (ex. 1.00)
/// ligne 2 : portée (LOC / REG / GLOB)
/// ligne 3 : N = nombre d'adjudications
/// N lignes : num⇥référence⇥intitulé⇥date⇥montant
/// 1 ligne  : M = nombre de lignes de statistiques
/// M lignes : liste⇥chapStdId⇥posteStdId⇥variante⇥cas⇥quantité⇥prixMin⇥prixMax
/// </code>
/// (séparateur : tabulation ; décimales à la virgule ; date dd-MM-yy HH:mm:ss).
/// </summary>
public static class StatistiquesMaoParser
{
    private static readonly CultureInfo Fr = CultureInfo.GetCultureInfo("fr-FR");

    public static FichierStatistiques Analyser(IEnumerable<string> lignes)
    {
        using var e = lignes.GetEnumerator();

        var version = LireNonVide(e);
        var portee = LireNonVide(e);
        var nbAdj = int.Parse(LireNonVide(e), Fr);

        var adjudications = new List<Adjudication>(nbAdj);
        for (int i = 0; i < nbAdj; i++)
        {
            var c = LireNonVide(e).Split('\t');
            adjudications.Add(new Adjudication
            {
                Numero = ParseInt(c, 0),
                Reference = Champ(c, 1),
                Intitule = Champ(c, 2),
                DateAdjudication = ParseDate(Champ(c, 3)),
                Montant = ParseDec(c, 4),
                Portee = portee,
            });
        }

        var nbStat = int.Parse(LireNonVide(e), Fr);
        var stats = new List<StatistiquePrix>(nbStat);
        for (int i = 0; i < nbStat; i++)
        {
            if (!e.MoveNext()) break;
            var ligne = e.Current;
            if (string.IsNullOrWhiteSpace(ligne)) { i--; continue; }
            var c = ligne.Split('\t');
            if (c.Length < 8) continue;
            // Les 2 colonnes de prix sont deux bornes dont l'ordre n'est pas garanti
            // dans le fichier source : on normalise en (min, max).
            var prixA = ParseDec(c, 6);
            var prixB = ParseDec(c, 7);
            stats.Add(new StatistiquePrix
            {
                Liste = Champ(c, 0),
                ChapitreStdId = ParseInt(c, 1),
                PosteStdId = ParseInt(c, 2),
                Variante = ParseInt(c, 3),
                NumeroCas = ParseInt(c, 4),
                Quantite = ParseDec(c, 5),
                PrixMin = Math.Min(prixA, prixB),
                PrixMax = Math.Max(prixA, prixB),
            });
        }

        return new FichierStatistiques(version, portee, adjudications, stats);
    }

    private static string LireNonVide(IEnumerator<string> e)
    {
        while (e.MoveNext())
        {
            var l = e.Current?.Trim();
            if (!string.IsNullOrEmpty(l)) return l;
        }
        throw new InvalidDataException("Fin de fichier inattendue dans le fichier statistiques.");
    }

    private static string Champ(string[] c, int i) => i < c.Length ? c[i].Trim() : string.Empty;
    private static int ParseInt(string[] c, int i) => int.TryParse(Champ(c, i), out var v) ? v : 0;

    private static decimal ParseDec(string[] c, int i) =>
        decimal.TryParse(Champ(c, i).Replace('.', ','), NumberStyles.Any, Fr, out var v) ? v : 0m;

    private static DateTime ParseDate(string s)
    {
        foreach (var fmt in new[] { "dd-MM-yy HH:mm:ss", "dd-MM-yyyy HH:mm:ss", "dd-MM-yy", "dd-MM-yyyy" })
            if (DateTime.TryParseExact(s, fmt, Fr, DateTimeStyles.None, out var d)) return d;
        return DateTime.TryParse(s, Fr, DateTimeStyles.None, out var dd) ? dd : default;
    }
}
