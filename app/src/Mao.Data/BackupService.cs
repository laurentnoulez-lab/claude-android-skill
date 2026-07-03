using System.Text.Json;
using System.Text.Json.Serialization;
using Mao.Domain.Entities;
using Microsoft.EntityFrameworkCore;

namespace Mao.Data;

/// <summary>Instantané complet de la base, pour sauvegarde/restauration JSON portable.</summary>
public class BackupData
{
    public List<Metre> Metres { get; set; } = new();
    public List<PosteStd> Catalogue { get; set; } = new();
    public List<Tva> TauxTva { get; set; } = new();
    public List<FormuleReference> Formules { get; set; } = new();
    public List<Indice> Indices { get; set; } = new();
    public List<Utilisateur> Utilisateurs { get; set; } = new();
    public List<EntiteAdmin> Entites { get; set; } = new();
    public List<Parametre> Parametres { get; set; } = new();
    public List<Adjudication> Adjudications { get; set; } = new();
    public List<StatistiquePrix> Statistiques { get; set; } = new();
}

/// <summary>
/// Exporte / importe l'intégralité des données de MAO Moderne dans un fichier
/// JSON (sauvegarde portable, échange entre postes, archivage).
/// </summary>
public class BackupService
{
    private readonly MaoDbContext _ctx;

    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = false,
        ReferenceHandler = ReferenceHandler.IgnoreCycles,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public BackupService(MaoDbContext ctx) => _ctx = ctx;

    public BackupData Construire() => new()
    {
        Metres = _ctx.Metres
            .Include(m => m.Divisions).ThenInclude(d => d.Chapitres).ThenInclude(c => c.Postes)
            .Include(m => m.FormulesRevision)
            .Include(m => m.PrixDechets)
            .AsNoTracking().ToList(),
        Catalogue = _ctx.PostesStd.AsNoTracking().ToList(),
        TauxTva = _ctx.TauxTva.AsNoTracking().ToList(),
        Formules = _ctx.FormulesReference.Include(f => f.Termes).AsNoTracking().ToList(),
        Indices = _ctx.Indices.AsNoTracking().ToList(),
        Utilisateurs = _ctx.Utilisateurs.AsNoTracking().ToList(),
        Entites = _ctx.Entites.Include(e => e.Agents).AsNoTracking().ToList(),
        Parametres = _ctx.Parametres.AsNoTracking().ToList(),
        Adjudications = _ctx.Adjudications.AsNoTracking().ToList(),
        Statistiques = _ctx.StatistiquesPrix.AsNoTracking().ToList(),
    };

    public void Exporter(string chemin)
        => File.WriteAllText(chemin, JsonSerializer.Serialize(Construire(), Json));

    /// <summary>Remplace toutes les données par celles du fichier de sauvegarde.</summary>
    public void Importer(string chemin)
    {
        var data = JsonSerializer.Deserialize<BackupData>(File.ReadAllText(chemin), Json)
                   ?? new BackupData();
        Restaurer(data);
    }

    public void Restaurer(BackupData data)
    {
        // Vidage dans un ordre respectant les clés étrangères.
        _ctx.StatistiquesPrix.RemoveRange(_ctx.StatistiquesPrix);
        _ctx.Adjudications.RemoveRange(_ctx.Adjudications);
        _ctx.FormuleTermes.RemoveRange(_ctx.FormuleTermes);
        _ctx.FormulesReference.RemoveRange(_ctx.FormulesReference);
        _ctx.Indices.RemoveRange(_ctx.Indices);
        _ctx.Agents.RemoveRange(_ctx.Agents);
        _ctx.Entites.RemoveRange(_ctx.Entites);
        _ctx.Utilisateurs.RemoveRange(_ctx.Utilisateurs);
        _ctx.Parametres.RemoveRange(_ctx.Parametres);
        _ctx.Postes.RemoveRange(_ctx.Postes);
        _ctx.Chapitres.RemoveRange(_ctx.Chapitres);
        _ctx.Divisions.RemoveRange(_ctx.Divisions);
        _ctx.Metres.RemoveRange(_ctx.Metres);
        _ctx.PostesStd.RemoveRange(_ctx.PostesStd);
        _ctx.TauxTva.RemoveRange(_ctx.TauxTva);
        _ctx.SaveChanges();

        // Réinsertion. Les graphes (Metre→Division→…, Formule→Termes,
        // Entite→Agents) sont ajoutés tels quels : EF régénère les identifiants
        // et recâble les clés étrangères automatiquement.
        ResetIds(data);

        _ctx.TauxTva.AddRange(data.TauxTva);
        _ctx.PostesStd.AddRange(data.Catalogue);
        _ctx.Parametres.AddRange(data.Parametres);
        _ctx.Metres.AddRange(data.Metres);
        _ctx.FormulesReference.AddRange(data.Formules);
        _ctx.Indices.AddRange(data.Indices);
        _ctx.Utilisateurs.AddRange(data.Utilisateurs);
        _ctx.Entites.AddRange(data.Entites);
        _ctx.Adjudications.AddRange(data.Adjudications);
        _ctx.StatistiquesPrix.AddRange(data.Statistiques);
        _ctx.SaveChanges();
    }

    /// <summary>Remet à 0 les clés auto-générées pour laisser EF les régénérer proprement.</summary>
    private static void ResetIds(BackupData d)
    {
        foreach (var m in d.Metres)
        {
            m.Id = 0;
            foreach (var div in m.Divisions)
            {
                div.Id = 0;
                foreach (var c in div.Chapitres)
                {
                    c.Id = 0;
                    foreach (var p in c.Postes) p.Id = 0;
                }
            }
            foreach (var f in m.FormulesRevision) { f.Id = 0; f.MetreId = 0; }
            foreach (var p in m.PrixDechets) { p.Id = 0; p.MetreId = 0; }
        }
        foreach (var f in d.Formules) { f.Id = 0; foreach (var t in f.Termes) t.Id = 0; }
        foreach (var i in d.Indices) i.Id = 0;
        foreach (var u in d.Utilisateurs) u.Id = 0;
        foreach (var e in d.Entites) { e.Id = 0; foreach (var a in e.Agents) a.Id = 0; }
        foreach (var a in d.Adjudications) a.Id = 0;
        foreach (var s in d.Statistiques) s.Id = 0;
    }
}
