using System.IO.Compression;
using System.Text.Json;
using Mao.Domain.Entities;

namespace Mao.Data;

/// <summary>
/// Charge le référentiel repris de MAO.db : types et codes de déchets,
/// indices salaires/matériaux réels, formules de référence TP.
/// </summary>
public static class ReferentielSeed
{
    private const string Ressource = "Mao.Data.Resources.referentiel.json.gz";

    private class Paquet
    {
        public List<TypeDechet> TypesDechets { get; set; } = new();
        public List<CodeDechet> CodesDechets { get; set; } = new();
        public List<Indice> Indices { get; set; } = new();
        public List<FormuleReference> FormulesReference { get; set; } = new();
    }

    public static void Appliquer(MaoDbContext ctx)
    {
        if (ctx.TypesDechets.Any()) return;

        using var flux = typeof(ReferentielSeed).Assembly.GetManifestResourceStream(Ressource);
        if (flux is null) return;
        using var gz = new GZipStream(flux, CompressionMode.Decompress);
        var paquet = JsonSerializer.Deserialize<Paquet>(gz,
            new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        if (paquet is null) return;

        ctx.TypesDechets.AddRange(paquet.TypesDechets);
        ctx.CodesDechets.AddRange(paquet.CodesDechets);
        if (!ctx.Indices.Any()) ctx.Indices.AddRange(paquet.Indices);
        if (!ctx.FormulesReference.Any()) ctx.FormulesReference.AddRange(paquet.FormulesReference);
        ctx.SaveChanges();
    }
}
