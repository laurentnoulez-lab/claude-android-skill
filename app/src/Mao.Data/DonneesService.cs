namespace Mao.Data;

/// <summary>Façade des opérations d'import/export de données (menu « Données »).</summary>
public class DonneesService
{
    private readonly MaoDbContext _ctx;

    public DonneesService(MaoDbContext ctx) => _ctx = ctx;

    /// <summary>Exporte toutes les données dans un fichier de sauvegarde JSON.</summary>
    public void ExporterSauvegarde(string chemin) => new BackupService(_ctx).Exporter(chemin);

    /// <summary>Remplace toutes les données par une sauvegarde JSON.</summary>
    public void ImporterSauvegarde(string chemin) => new BackupService(_ctx).Importer(chemin);

    /// <summary>Importe directement une base MAO.db (Sybase via ODBC).</summary>
    public RapportImport ImporterMaoDb(string chemin) => new SybaseImporter(_ctx).Importer(chemin);

    /// <summary>Importe/met à jour le catalogue depuis un fichier JSON.</summary>
    public int ImporterCatalogueJson(string chemin) => new CatalogueImporter(_ctx).ImporterDepuisFichier(chemin);
}
