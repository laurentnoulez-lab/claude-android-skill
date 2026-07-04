using System.Globalization;
using Mao.Domain.Entities;

namespace Mao.Domain.Services;

/// <summary>
/// Lecture/écriture du format d'échange de métré « .MAO » de MAO V8
/// (fenêtres w_export_metre / w_import_metre, filtre « MAO Files (*.MAO) »).
///
/// Structure (même convention que le fichier statistiques de MAO :
/// séparateur tabulation, décimales indifféremment « . » ou « , »,
/// chaque section précédée de son nombre de lignes) :
/// <code>
/// 1.00                                    version
/// 1                                       nb de métrés (toujours 1)
/// ligne métré (l_intitule, description, csc, o_tva_identique, c_taux_tva,
///              c_liste_standardisee, c_cct)
/// N  puis N lignes division   : id ⇥ numéro ⇥ intitulé
/// N  puis N lignes chapitre   : id ⇥ id_division ⇥ numéro ⇥ intitulé
/// N  puis N lignes poste      : id_chapitre ⇥ numéro ⇥ code_std ⇥ intitulé ⇥
///     description ⇥ unité ⇥ quantité ⇥ prix_unitaire ⇥ type_prix ⇥ taux_tva ⇥
///     type_déchet ⇥ coef_déchet ⇥ n°_formule_révision ⇥ généré(O/N)
/// N  puis N lignes formule    : numéro ⇥ type ⇥ intitulé ⇥ A ⇥ B ⇥ C ⇥ A1 ⇥
///     A2 ⇥ B1 ⇥ B2 ⇥ C2 ⇥ D
/// N  puis N lignes prix déchet: code ⇥ prix
/// </code>
/// L'analyseur est tolérant : colonnes excédentaires ignorées, colonnes
/// manquantes traitées comme vides, lignes vides ignorées.
/// </summary>
public static class MaoFichierMetre
{
    public const string Version = "1.00";
    private static readonly CultureInfo Inv = CultureInfo.InvariantCulture;

    // ---------- Écriture ----------

    public static string Ecrire(Metre metre)
    {
        var sb = new System.Text.StringBuilder();
        sb.AppendLine(Version);
        sb.AppendLine("1");
        sb.AppendLine(string.Join('\t',
            N(metre.Intitule), "", "", metre.TvaIdentique ? "O" : "N",
            metre.TauxTvaCode ?? "", metre.ListeNormalisee, metre.CodeCct ?? ""));

        var divisions = metre.Divisions.OrderBy(d => d.Numero).ToList();
        sb.AppendLine(divisions.Count.ToString(Inv));
        var idDiv = new Dictionary<Division, int>();
        for (int i = 0; i < divisions.Count; i++)
        {
            idDiv[divisions[i]] = i + 1;
            sb.AppendLine($"{i + 1}\t{divisions[i].Numero}\t{N(divisions[i].Intitule)}");
        }

        var chapitres = divisions.SelectMany(d => d.Chapitres.OrderBy(c => c.Numero)
            .Select(c => (Div: d, Chap: c))).ToList();
        sb.AppendLine(chapitres.Count.ToString(Inv));
        var idChap = new Dictionary<Chapitre, int>();
        for (int i = 0; i < chapitres.Count; i++)
        {
            idChap[chapitres[i].Chap] = i + 1;
            sb.AppendLine($"{i + 1}\t{idDiv[chapitres[i].Div]}\t{chapitres[i].Chap.Numero}\t{N(chapitres[i].Chap.Intitule)}");
        }

        var postes = chapitres.SelectMany(cc => cc.Chap.Postes.OrderBy(p => p.Numero)
            .Select(p => (Chap: cc.Chap, Poste: p))).ToList();
        sb.AppendLine(postes.Count.ToString(Inv));
        foreach (var (chap, p) in postes)
            sb.AppendLine(string.Join('\t',
                idChap[chap].ToString(Inv), p.Numero.ToString(Inv), p.CodePosteStd ?? "",
                N(p.Intitule), N(p.Description ?? ""), p.Unite,
                p.QuantitePresumee.ToString(Inv), p.PrixUnitaire.ToString(Inv),
                p.TypePrix, p.TauxTvaCode ?? "",
                p.TypeDechetId?.ToString(Inv) ?? "", p.CoefConversionDechet?.ToString(Inv) ?? "",
                p.FormuleRevisionNumero?.ToString(Inv) ?? "", p.EstGenere ? "O" : "N"));

        sb.AppendLine(metre.FormulesRevision.Count.ToString(Inv));
        foreach (var f in metre.FormulesRevision.OrderBy(f => f.Numero))
            sb.AppendLine(string.Join('\t',
                f.Numero.ToString(Inv), f.Type, N(f.Intitule),
                D(f.A), D(f.B), D(f.C), D(f.A1), D(f.A2), D(f.B1), D(f.B2), D(f.C2), D(f.D)));

        sb.AppendLine(metre.PrixDechets.Count.ToString(Inv));
        foreach (var pd in metre.PrixDechets)
            sb.AppendLine($"{pd.CodePosteStd}\t{pd.Prix.ToString(Inv)}");

        return sb.ToString();
    }

    // ---------- Lecture ----------

    public static Metre Lire(IEnumerable<string> lignes)
    {
        using var e = lignes.Where(l => !string.IsNullOrWhiteSpace(l)).GetEnumerator();
        string Suiv() => e.MoveNext() ? e.Current
            : throw new InvalidDataException("Fichier .mao incomplet.");

        var version = Suiv().Trim();
        if (!version.StartsWith("1.") && !version.StartsWith("8."))
            throw new InvalidDataException($"Version de fichier .mao non reconnue : « {version} ».");
        _ = Entier(Suiv()); // nb de métrés (1)

        var cm = Suiv().Split('\t');
        var metre = new Metre
        {
            Intitule = Champ(cm, 0),
            TvaIdentique = Champ(cm, 3) == "O",
            TauxTvaCode = Vide(Champ(cm, 4)),
            ListeNormalisee = Champ(cm, 5) is { Length: > 0 } l ? l : "RW99",
            CodeCct = Vide(Champ(cm, 6)),
            DerniereMaj = DateTime.Now,
        };

        var divParId = new Dictionary<int, Division>();
        int n = Entier(Suiv());
        for (int i = 0; i < n; i++)
        {
            var c = Suiv().Split('\t');
            var div = new Division { Numero = Entier(Champ(c, 1)), Intitule = Champ(c, 2) };
            divParId[Entier(Champ(c, 0))] = div;
            metre.Divisions.Add(div);
        }

        var chapParId = new Dictionary<int, Chapitre>();
        n = Entier(Suiv());
        for (int i = 0; i < n; i++)
        {
            var c = Suiv().Split('\t');
            var chap = new Chapitre { Numero = Entier(Champ(c, 2)), Intitule = Champ(c, 3) };
            chapParId[Entier(Champ(c, 0))] = chap;
            (divParId.TryGetValue(Entier(Champ(c, 1)), out var div) ? div : DivisionDefaut(metre))
                .Chapitres.Add(chap);
        }

        n = Entier(Suiv());
        for (int i = 0; i < n; i++)
        {
            var c = Suiv().Split('\t');
            var poste = new Poste
            {
                Numero = Entier(Champ(c, 1)),
                CodePosteStd = Vide(Champ(c, 2)),
                Intitule = Champ(c, 3),
                Description = Vide(Champ(c, 4)),
                Unite = Champ(c, 5),
                QuantitePresumee = Decimale(Champ(c, 6)),
                PrixUnitaire = Decimale(Champ(c, 7)),
                TypePrix = Champ(c, 8) is { Length: > 0 } tp ? tp : "QP",
                TauxTvaCode = Vide(Champ(c, 9)),
                TypeDechetId = EntierNull(Champ(c, 10)),
                CoefConversionDechet = DecimaleNull(Champ(c, 11)),
                FormuleRevisionNumero = EntierNull(Champ(c, 12)),
                EstGenere = Champ(c, 13) == "O",
            };
            poste.EstNormalise = poste.CodePosteStd is not null;
            (chapParId.TryGetValue(Entier(Champ(c, 0)), out var chap) ? chap : ChapitreDefaut(metre))
                .Postes.Add(poste);
        }

        n = Entier(Suiv());
        for (int i = 0; i < n; i++)
        {
            var c = Suiv().Split('\t');
            metre.FormulesRevision.Add(new FormuleRevisionMetre
            {
                Numero = Entier(Champ(c, 0)),
                Type = Champ(c, 1) is { Length: > 0 } ty ? ty : "1",
                Intitule = Champ(c, 2),
                A = DecimaleNull(Champ(c, 3)), B = DecimaleNull(Champ(c, 4)), C = DecimaleNull(Champ(c, 5)),
                A1 = DecimaleNull(Champ(c, 6)), A2 = DecimaleNull(Champ(c, 7)),
                B1 = DecimaleNull(Champ(c, 8)), B2 = DecimaleNull(Champ(c, 9)),
                C2 = DecimaleNull(Champ(c, 10)), D = DecimaleNull(Champ(c, 11)),
            });
        }

        n = Entier(Suiv());
        for (int i = 0; i < n; i++)
        {
            var c = Suiv().Split('\t');
            metre.PrixDechets.Add(new PrixPosteDechet
            {
                CodePosteStd = Champ(c, 0),
                Prix = Decimale(Champ(c, 1)),
            });
        }

        return metre;
    }

    // ---------- Aides ----------

    private static Division DivisionDefaut(Metre m)
    {
        var d = m.Divisions.FirstOrDefault();
        if (d is null) { d = new Division { Numero = 1, Intitule = "Division 1" }; m.Divisions.Add(d); }
        return d;
    }

    private static Chapitre ChapitreDefaut(Metre m)
    {
        var div = DivisionDefaut(m);
        var c = div.Chapitres.FirstOrDefault();
        if (c is null) { c = new Chapitre { Numero = 1, Intitule = "Chapitre 1" }; div.Chapitres.Add(c); }
        return c;
    }

    /// <summary>Neutralise tabulations/retours ligne dans un champ texte.</summary>
    private static string N(string s) => s.Replace('\t', ' ').Replace('\r', ' ').Replace('\n', ' ');
    private static string D(decimal? v) => v?.ToString(Inv) ?? "";
    private static string Champ(string[] c, int i) => i < c.Length ? c[i].Trim() : "";
    private static string? Vide(string s) => s.Length == 0 ? null : s;
    private static int Entier(string s) => int.TryParse(s.Trim(), out var v) ? v : 0;
    private static int? EntierNull(string s) => int.TryParse(s.Trim(), out var v) ? v : null;

    private static decimal Decimale(string s) =>
        decimal.TryParse(s.Trim().Replace(',', '.'), NumberStyles.Any, Inv, out var v) ? v : 0m;

    private static decimal? DecimaleNull(string s) =>
        decimal.TryParse(s.Trim().Replace(',', '.'), NumberStyles.Any, Inv, out var v) ? v : null;
}
