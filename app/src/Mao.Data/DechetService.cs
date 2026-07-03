using Mao.Domain.Entities;
using Mao.Domain.Services;

namespace Mao.Data;

/// <summary>Génération des postes déchets (D9000) — reproduit MAO V8.</summary>
public class DechetService
{
    private readonly MaoDbContext _ctx;

    public DechetService(MaoDbContext ctx) => _ctx = ctx;

    /// <summary>Génère (ou régénère) les postes déchets du métré et enregistre.</summary>
    public RapportGenerationDechets Generer(Metre metre)
    {
        var generator = new DechetGenerator(
            _ctx.CodesDechets.ToList(),
            _ctx.PostesStd.Where(p => p.Code.StartsWith("D9"))
                .ToDictionary(p => p.Code, p => p));
        var rapport = generator.Generer(metre);
        _ctx.SaveChanges();
        return rapport;
    }

    public List<TypeDechet> ListerTypes() =>
        _ctx.TypesDechets.OrderBy(t => t.Id).ToList();
}
