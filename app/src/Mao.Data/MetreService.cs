using Mao.Domain.Entities;
using Mao.Domain.Services;
using Microsoft.EntityFrameworkCore;

namespace Mao.Data;

/// <summary>Opérations de haut niveau sur les métrés (module « Gestion des métrés »).</summary>
public class MetreService
{
    private readonly MaoDbContext _ctx;

    public MetreService(MaoDbContext ctx) => _ctx = ctx;

    /// <summary>Liste légère des métrés (sans charger toute la hiérarchie).</summary>
    public List<Metre> ListerMetres() =>
        _ctx.Metres.OrderByDescending(m => m.DerniereMaj).ToList();

    /// <summary>Charge un métré avec toute sa hiérarchie Division/Chapitre/Poste.</summary>
    public Metre? ChargerComplet(int id) =>
        _ctx.Metres
            .Include(m => m.Divisions.OrderBy(d => d.Numero))
                .ThenInclude(d => d.Chapitres.OrderBy(c => c.Numero))
                    .ThenInclude(c => c.Postes.OrderBy(p => p.Numero))
            .FirstOrDefault(m => m.Id == id);

    public Metre CreerMetre(string intitule)
    {
        var metre = new Metre
        {
            Intitule = intitule,
            TvaIdentique = true,
            TauxTvaCode = "21",
            DerniereMaj = DateTime.Now,
        };
        _ctx.Metres.Add(metre);
        _ctx.SaveChanges();
        return metre;
    }

    public void SupprimerMetre(int id)
    {
        var metre = _ctx.Metres.Find(id);
        if (metre is null) return;
        _ctx.Metres.Remove(metre);
        _ctx.SaveChanges();
    }

    public void Enregistrer(Metre metre)
    {
        metre.DerniereMaj = DateTime.Now;
        _ctx.SaveChanges();
    }

    /// <summary>Table code de taux → taux fractionnaire, pour alimenter le <see cref="MetreCalculator"/>.</summary>
    public Dictionary<string, decimal> ChargerTauxTva() =>
        _ctx.TauxTva.ToDictionary(t => t.Code, t => t.Taux);

    public MetreCalculator CreerCalculateur() => new(ChargerTauxTva());
}
