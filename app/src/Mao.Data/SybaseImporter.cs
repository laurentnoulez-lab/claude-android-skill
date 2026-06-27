using System.Data;
using System.Data.Odbc;
using Mao.Domain.Entities;

namespace Mao.Data;

/// <summary>Compte rendu d'un import depuis une base MAO V8 (Sybase).</summary>
public record RapportImport(bool Succes, string Message, int Postes, int Metres);

/// <summary>
/// Importe directement une base <c>MAO.db</c> (Sybase SQL Anywhere) via ODBC.
/// Nécessite le pilote ODBC SQL Anywhere installé sur le poste (présent là où
/// MAO V8 est installé). À défaut, l'import échoue proprement avec un message
/// invitant à fournir un export <c>dbunload</c> (.sql) ou une sauvegarde JSON.
/// </summary>
public class SybaseImporter
{
    private readonly MaoDbContext _ctx;

    /// <summary>Noms de pilote ODBC SQL Anywhere essayés successivement.</summary>
    private static readonly string[] Pilotes =
    {
        "SQL Anywhere 6", "Adaptive Server Anywhere 6.0", "Sybase SQL Anywhere 6.0",
        "Sybase SQL Anywhere 5.0", "SQL Anywhere 5",
    };

    public SybaseImporter(MaoDbContext ctx) => _ctx = ctx;

    /// <summary>Tente d'ouvrir la base Sybase et d'importer catalogue + métrés.</summary>
    public RapportImport Importer(string cheminDb, string uid = "DBA", string pwd = "SQL")
    {
        OdbcConnection? cn = null;
        try
        {
            cn = Ouvrir(cheminDb, uid, pwd);
            if (cn is null)
                return new RapportImport(false,
                    "Aucun pilote ODBC SQL Anywhere n'a pu ouvrir cette base. " +
                    "Installez le pilote (présent sur le poste MAO V8) ou utilisez " +
                    "un export dbunload (.sql) / une sauvegarde JSON.", 0, 0);

            var postes = ImporterCatalogue(cn);
            var metres = ImporterMetres(cn);
            _ctx.SaveChanges();
            return new RapportImport(true,
                $"Import réussi : {postes} poste(s) de catalogue, {metres} métré(s).", postes, metres);
        }
        catch (Exception ex)
        {
            return new RapportImport(false, "Échec de l'import Sybase : " + ex.Message, 0, 0);
        }
        finally
        {
            cn?.Dispose();
        }
    }

    private static OdbcConnection? Ouvrir(string cheminDb, string uid, string pwd)
    {
        foreach (var pilote in Pilotes)
        {
            try
            {
                var cs = $"Driver={{{pilote}}};DBF={cheminDb};UID={uid};PWD={pwd};";
                var cn = new OdbcConnection(cs);
                cn.Open();
                return cn;
            }
            catch (OdbcException) { /* pilote suivant */ }
        }
        return null;
    }

    private int ImporterCatalogue(OdbcConnection cn)
    {
        var postes = new List<PosteStd>();
        using (var cmd = new OdbcCommand("SELECT * FROM POSTE_STD", cn))
        using (var r = cmd.ExecuteReader())
        {
            var noms = NomsColonnes(r);
            while (r.Read())
                postes.Add(SybaseMapping.MapPosteStd(n => Valeur(r, noms, n)));
        }
        return new CatalogueImporter(_ctx).Upsert(postes);
    }

    private int ImporterMetres(OdbcConnection cn)
    {
        // Best-effort : la structure exacte des tables métré peut varier selon
        // la version. On lit ce qui est disponible et on recâble la hiérarchie.
        var metres = LireTable(cn, "METRE");
        int n = 0;
        foreach (var (cle, get) in metres)
        {
            var metre = SybaseMapping.MapMetre(get);
            if (string.IsNullOrWhiteSpace(metre.Intitule)) metre.Intitule = $"Métré {cle}";
            _ctx.Metres.Add(metre);
            n++;
        }
        return n;
    }

    /// <summary>Lit une table en mémoire : (clé première colonne, accesseur par nom).</summary>
    private static List<(object Cle, SybaseMapping.Colonne Get)> LireTable(OdbcConnection cn, string table)
    {
        var lignes = new List<(object, SybaseMapping.Colonne)>();
        try
        {
            using var cmd = new OdbcCommand($"SELECT * FROM {table}", cn);
            using var r = cmd.ExecuteReader();
            var noms = NomsColonnes(r);
            while (r.Read())
            {
                var vals = new Dictionary<string, object?>(StringComparer.OrdinalIgnoreCase);
                foreach (var kv in noms) vals[kv.Key] = r.IsDBNull(kv.Value) ? null : r.GetValue(kv.Value);
                var snapshot = vals; // capture
                lignes.Add((r.GetValue(0), n => snapshot.TryGetValue(n, out var v) ? v : null));
            }
        }
        catch (OdbcException) { /* table absente : ignorée */ }
        return lignes;
    }

    private static Dictionary<string, int> NomsColonnes(IDataReader r)
    {
        var d = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        for (int i = 0; i < r.FieldCount; i++) d[r.GetName(i)] = i;
        return d;
    }

    private static object? Valeur(IDataReader r, Dictionary<string, int> noms, string nom)
        => noms.TryGetValue(nom, out var i) && !r.IsDBNull(i) ? r.GetValue(i) : null;
}
