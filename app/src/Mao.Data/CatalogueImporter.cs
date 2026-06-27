using System.Text.Json;
using Mao.Domain.Entities;

namespace Mao.Data;

/// <summary>
/// Importe le catalogue normalisé complet depuis un fichier JSON (liste de
/// postes). Permet de charger le vrai catalogue RW99 issu de <c>MAO.db</c>
/// (export <c>dbunload</c> → conversion JSON) sans dépendre d'un <c>.exe</c>
/// de mise à jour par révision, comme dans MAO V8.
/// </summary>
public class CatalogueImporter
{
    private readonly MaoDbContext _ctx;

    public CatalogueImporter(MaoDbContext ctx) => _ctx = ctx;

    /// <summary>Importe (upsert) depuis un fichier JSON. Retourne le nombre de postes traités.</summary>
    public int ImporterDepuisFichier(string cheminJson)
    {
        using var flux = File.OpenRead(cheminJson);
        return Importer(flux);
    }

    public int Importer(Stream jsonStream)
    {
        var postes = JsonSerializer.Deserialize<List<PosteStd>>(jsonStream, OptionsJson)
                     ?? new List<PosteStd>();
        return Upsert(postes);
    }

    public int Upsert(IEnumerable<PosteStd> postes)
    {
        int n = 0;
        foreach (var p in postes)
        {
            if (string.IsNullOrWhiteSpace(p.Code)) continue;
            var existant = _ctx.PostesStd.Find(p.Code);
            if (existant is null)
            {
                _ctx.PostesStd.Add(p);
            }
            else
            {
                existant.ListeStandardisee = p.ListeStandardisee;
                existant.ChapitreStdId = p.ChapitreStdId;
                existant.PosteStdId = p.PosteStdId;
                existant.Intitule = p.Intitule;
                existant.Description = p.Description;
                existant.Liste = p.Liste;
                existant.Unite = p.Unite;
                existant.TypePrix = p.TypePrix;
                existant.FormuleRefId = p.FormuleRefId;
                existant.CoefConvMin = p.CoefConvMin;
                existant.CoefConvMax = p.CoefConvMax;
                existant.CoefConvPropose = p.CoefConvPropose;
                existant.TypeDechetId = p.TypeDechetId;
                existant.SupprimeRw03 = p.SupprimeRw03;
            }
            n++;
        }
        _ctx.SaveChanges();
        return n;
    }

    private static readonly JsonSerializerOptions OptionsJson = new()
    {
        PropertyNameCaseInsensitive = true,
    };
}
