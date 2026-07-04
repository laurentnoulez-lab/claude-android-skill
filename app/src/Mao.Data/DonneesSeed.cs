using System.IO.Compression;
using System.Text.Json;
using Mao.Domain.Entities;
using Microsoft.EntityFrameworkCore;

namespace Mao.Data;

/// <summary>
/// Charge les données utilisateur reprises de MAO.db (701 métrés et leur
/// hiérarchie, taux de TVA réels) depuis la ressource embarquée, au premier
/// lancement. Conserve le catalogue déjà chargé.
/// </summary>
public static class DonneesSeed
{
    private const string Ressource = "Mao.Data.Resources.donnees_mao.json.gz";

    private class Paquet
    {
        public List<Metre> Metres { get; set; } = new();
        public List<Tva> TauxTva { get; set; } = new();
    }

    public static void Appliquer(MaoDbContext ctx)
    {
        if (ctx.Metres.Any()) return;

        using var flux = typeof(DonneesSeed).Assembly.GetManifestResourceStream(Ressource);
        if (flux is null) return;
        using var gz = new GZipStream(flux, CompressionMode.Decompress);

        var paquet = JsonSerializer.Deserialize<Paquet>(gz,
            new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        if (paquet is null) return;

        // Fusion des taux de TVA réels (codes « 1 » = 21 %, « 2 » = 0 %…).
        var codesExistants = ctx.TauxTva.Select(t => t.Code).ToHashSet();
        foreach (var t in paquet.TauxTva)
            if (!string.IsNullOrEmpty(t.Code) && codesExistants.Add(t.Code))
                ctx.TauxTva.Add(t);

        // Insertion en masse de la hiérarchie (perf : détection de changements off).
        var auto = ctx.ChangeTracker.AutoDetectChangesEnabled;
        ctx.ChangeTracker.AutoDetectChangesEnabled = false;
        try
        {
            ctx.Metres.AddRange(paquet.Metres);
            ctx.SaveChanges();
        }
        finally
        {
            ctx.ChangeTracker.AutoDetectChangesEnabled = auto;
        }
    }
}
