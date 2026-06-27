using Mao.Domain.Entities;
using Mao.Domain.Services;
using Microsoft.EntityFrameworkCore;

namespace Mao.Data;

/// <summary>Gestion des indices et formules de révision de prix.</summary>
public class RevisionService
{
    private readonly MaoDbContext _ctx;

    public RevisionService(MaoDbContext ctx) => _ctx = ctx;

    // --- Indices ---

    public List<Indice> ListerIndices(TypeIndice? type = null)
    {
        IQueryable<Indice> q = _ctx.Indices;
        if (type is not null) q = q.Where(i => i.Type == type);
        return q.OrderBy(i => i.Type).ThenBy(i => i.Code).ThenBy(i => i.Periode).ToList();
    }

    public Indice EnregistrerIndice(Indice indice)
    {
        if (indice.Periode != default)
            indice.Periode = new DateTime(indice.Periode.Year, indice.Periode.Month, 1);
        if (indice.Id == 0) _ctx.Indices.Add(indice);
        _ctx.SaveChanges();
        return indice;
    }

    public void SupprimerIndice(int id)
    {
        var i = _ctx.Indices.Find(id);
        if (i is null) return;
        _ctx.Indices.Remove(i);
        _ctx.SaveChanges();
    }

    /// <summary>Valeur d'un indice à une période (1er du mois). Lève si absente.</summary>
    public decimal ValeurIndice(TypeIndice type, string code, DateTime periode)
    {
        var p = new DateTime(periode.Year, periode.Month, 1);
        var i = _ctx.Indices.AsNoTracking()
            .FirstOrDefault(x => x.Type == type && x.Code == code && x.Periode == p);
        if (i is null)
            throw new InvalidOperationException($"Indice {type}/{code} introuvable pour {p:yyyy-MM}.");
        return i.Valeur;
    }

    // --- Formules ---

    public List<FormuleReference> ListerFormules() =>
        _ctx.FormulesReference.Include(f => f.Termes).OrderBy(f => f.Id).ToList();

    public FormuleReference EnregistrerFormule(FormuleReference formule)
    {
        if (formule.Id == 0) _ctx.FormulesReference.Add(formule);
        _ctx.SaveChanges();
        return formule;
    }

    // --- Calcul ---

    /// <summary>Calculateur de révision branché sur les indices stockés.</summary>
    public RevisionCalculator CreerCalculateur() => new(ValeurIndice);

    /// <summary>Persiste les modifications en attente (éditions inline des grilles).</summary>
    public void Sauvegarder() => _ctx.SaveChanges();
}
