using Mao.Domain.Entities;
using Microsoft.EntityFrameworkCore;

namespace Mao.Data;

/// <summary>Accès au catalogue normalisé (postes standardisés RW99/Qualiroutes).</summary>
public class CatalogueService
{
    private readonly MaoDbContext _ctx;

    public CatalogueService(MaoDbContext ctx) => _ctx = ctx;

    /// <summary>
    /// Recherche par mot-clé : code OU intitulé OU description (insensible à la casse).
    /// Vide → premiers postes par code. Reproduit « recherche par mot » de MAO V8.
    /// </summary>
    public List<PosteStd> Rechercher(string? terme, int limite = 200)
    {
        IQueryable<PosteStd> q = _ctx.PostesStd.AsNoTracking();
        if (!string.IsNullOrWhiteSpace(terme))
        {
            var t = terme.Trim().ToLower();
            q = q.Where(p =>
                p.Code.ToLower().Contains(t) ||
                p.Intitule.ToLower().Contains(t) ||
                (p.Description != null && p.Description.ToLower().Contains(t)));
        }
        return q.OrderBy(p => p.Code).Take(limite).ToList();
    }

    public PosteStd? ParCode(string code) =>
        _ctx.PostesStd.AsNoTracking().FirstOrDefault(p => p.Code == code);

    public int Compte() => _ctx.PostesStd.Count();
}
