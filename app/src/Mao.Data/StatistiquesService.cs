using System.Globalization;
using System.Text;
using Mao.Domain.Entities;
using Mao.Domain.Services;
using Microsoft.EntityFrameworkCore;

namespace Mao.Data;

/// <summary>Agrégat min/max d'un poste, joint au catalogue pour l'affichage.</summary>
public record StatPosteVue(string? Code, string Intitule, int ChapitreStdId, int PosteStdId,
                           int Nombre, decimal PrixMin, decimal PrixMax);

/// <summary>Gestion des adjudications et statistiques de prix (format natif MAO).</summary>
public class StatistiquesService
{
    private readonly MaoDbContext _ctx;

    public StatistiquesService(MaoDbContext ctx) => _ctx = ctx;

    public List<Adjudication> ListerAdjudications() =>
        _ctx.Adjudications.OrderByDescending(a => a.DateAdjudication).ToList();

    /// <summary>
    /// Importe un fichier statistiques MAO (remplace les statistiques existantes).
    /// Résout le code de poste via le catalogue sur (ChapitreStdId, PosteStdId).
    /// Retourne le nombre de lignes de statistiques importées.
    /// </summary>
    public int ImporterFichierMao(string chemin)
    {
        var fichier = StatistiquesMaoParser.Analyser(File.ReadLines(chemin, Encoding.UTF8));

        // Remplacement complet (« chargement de l'ensemble des statistiques »).
        _ctx.StatistiquesPrix.RemoveRange(_ctx.StatistiquesPrix);
        _ctx.Adjudications.RemoveRange(_ctx.Adjudications);
        _ctx.SaveChanges();

        _ctx.Adjudications.AddRange(fichier.Adjudications);

        // Index catalogue (ChapitreStdId, PosteStdId) → Code, en une requête.
        var index = _ctx.PostesStd.AsNoTracking()
            .Where(p => p.Unite != "--")
            .Select(p => new { p.ChapitreStdId, p.PosteStdId, p.Code })
            .ToList()
            .GroupBy(p => (p.ChapitreStdId, p.PosteStdId))
            .ToDictionary(g => g.Key, g => g.First().Code);

        foreach (var s in fichier.Statistiques)
            if (index.TryGetValue((s.ChapitreStdId, s.PosteStdId), out var code))
                s.CodePosteStd = code;

        _ctx.StatistiquesPrix.AddRange(fichier.Statistiques);
        _ctx.SaveChanges();
        return fichier.Statistiques.Count;
    }

    /// <summary>Statistiques agrégées par poste (min des min, max des max), jointes au catalogue.</summary>
    public List<StatPosteVue> StatistiquesParPoste()
    {
        var stats = _ctx.StatistiquesPrix.AsNoTracking().ToList();
        var intitules = _ctx.PostesStd.AsNoTracking()
            .ToDictionary(p => p.Code, p => p.Intitule);

        return stats
            .GroupBy(s => (s.ChapitreStdId, s.PosteStdId, s.CodePosteStd))
            .Select(g =>
            {
                var code = g.Key.CodePosteStd;
                var intitule = code is not null && intitules.TryGetValue(code, out var it) ? it : "(non résolu)";
                return new StatPosteVue(code, intitule, g.Key.ChapitreStdId, g.Key.PosteStdId,
                    g.Count(), g.Min(x => x.PrixMin), g.Max(x => x.PrixMax));
            })
            .OrderBy(v => v.Code ?? "zzz")
            .ToList();
    }

    /// <summary>Fourchette de prix observée pour un code de poste (min, max), si disponible.</summary>
    public (decimal Min, decimal Max)? FourchettePrix(string code)
    {
        var lignes = _ctx.StatistiquesPrix.AsNoTracking().Where(s => s.CodePosteStd == code).ToList();
        if (lignes.Count == 0) return null;
        return (lignes.Min(s => s.PrixMin), lignes.Max(s => s.PrixMax));
    }

    public void Vider()
    {
        _ctx.StatistiquesPrix.RemoveRange(_ctx.StatistiquesPrix);
        _ctx.Adjudications.RemoveRange(_ctx.Adjudications);
        _ctx.SaveChanges();
    }

    /// <summary>Exporte les statistiques par poste en CSV (séparateur « ; »).</summary>
    public void ExporterCsv(string chemin)
    {
        var ci = CultureInfo.GetCultureInfo("fr-FR");
        using var w = new StreamWriter(chemin, false, new UTF8Encoding(true));
        w.WriteLine("Code;Intitulé;ChapitreStd;PosteStd;Nombre;Prix min;Prix max");
        foreach (var s in StatistiquesParPoste())
            w.WriteLine($"{s.Code};{Echap(s.Intitule)};{s.ChapitreStdId};{s.PosteStdId};{s.Nombre};{s.PrixMin.ToString("0.00", ci)};{s.PrixMax.ToString("0.00", ci)}");
    }

    private static string Echap(string s) =>
        s.Contains(';') || s.Contains('"') ? '"' + s.Replace("\"", "\"\"") + '"' : s;
}
