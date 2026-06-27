using System.IO.Compression;
using System.Reflection;

namespace Mao.Data;

/// <summary>
/// Charge le catalogue normalisé Qualiroutes (CPN, liste QR17 — 9 691 postes)
/// depuis la ressource embarquée gzippée, au premier lancement.
/// </summary>
public static class CatalogueSeed
{
    private const string Ressource = "Mao.Data.Resources.catalogue_qr.json.gz";

    public static void Appliquer(MaoDbContext ctx)
    {
        if (ctx.PostesStd.Any()) return;

        using var flux = typeof(CatalogueSeed).Assembly.GetManifestResourceStream(Ressource);
        if (flux is null) return; // pas de catalogue embarqué : base laissée vide
        using var gz = new GZipStream(flux, CompressionMode.Decompress);

        var importer = new CatalogueImporter(ctx);
        importer.Importer(gz);
    }
}
