using Mao.Domain.Entities;
using Microsoft.EntityFrameworkCore;

namespace Mao.Data;

/// <summary>Administration : utilisateurs, entités, agents, TVA, paramètres.</summary>
public class AdminService
{
    private readonly MaoDbContext _ctx;

    public AdminService(MaoDbContext ctx) => _ctx = ctx;

    // --- Utilisateurs ---
    public List<Utilisateur> ListerUtilisateurs() => _ctx.Utilisateurs.OrderBy(u => u.Code).ToList();

    public Utilisateur EnregistrerUtilisateur(Utilisateur u)
    {
        if (u.Id == 0) _ctx.Utilisateurs.Add(u);
        _ctx.SaveChanges();
        return u;
    }

    public void SupprimerUtilisateur(int id) => Supprimer(_ctx.Utilisateurs, id);

    // --- Entités ---
    public List<EntiteAdmin> ListerEntites() =>
        _ctx.Entites.Include(e => e.Agents).OrderBy(e => e.Code).ToList();

    public EntiteAdmin EnregistrerEntite(EntiteAdmin e)
    {
        if (e.Id == 0) _ctx.Entites.Add(e);
        _ctx.SaveChanges();
        return e;
    }

    public void SupprimerEntite(int id) => Supprimer(_ctx.Entites, id);

    // --- Agents ---
    public List<AgentAdmin> ListerAgents() => _ctx.Agents.OrderBy(a => a.Nom).ToList();

    public AgentAdmin EnregistrerAgent(AgentAdmin a)
    {
        if (a.Id == 0) _ctx.Agents.Add(a);
        _ctx.SaveChanges();
        return a;
    }

    public void SupprimerAgent(int id) => Supprimer(_ctx.Agents, id);

    // --- TVA ---
    public List<Tva> ListerTva() => _ctx.TauxTva.AsEnumerable().OrderByDescending(t => t.Taux).ToList();

    public Tva EnregistrerTva(Tva t)
    {
        if (!_ctx.TauxTva.Any(x => x.Code == t.Code)) _ctx.TauxTva.Add(t);
        _ctx.SaveChanges();
        return t;
    }

    public void SupprimerTva(string code)
    {
        var t = _ctx.TauxTva.Find(code);
        if (t is null) return;
        _ctx.TauxTva.Remove(t);
        _ctx.SaveChanges();
    }

    // --- Paramètres (remplace les .ini) ---
    public List<Parametre> ListerParametres() => _ctx.Parametres.OrderBy(p => p.Cle).ToList();

    public string? Parametre(string cle) => _ctx.Parametres.Find(cle)?.Valeur;

    public void DefinirParametre(string cle, string? valeur, string? description = null)
    {
        var p = _ctx.Parametres.Find(cle);
        if (p is null) _ctx.Parametres.Add(new Parametre { Cle = cle, Valeur = valeur, Description = description });
        else { p.Valeur = valeur; if (description is not null) p.Description = description; }
        _ctx.SaveChanges();
    }

    /// <summary>Persiste toutes les modifications en attente (éditions inline des grilles).</summary>
    public void Sauvegarder() => _ctx.SaveChanges();

    private void Supprimer<T>(DbSet<T> set, int id) where T : class
    {
        var e = set.Find(id);
        if (e is null) return;
        set.Remove(e);
        _ctx.SaveChanges();
    }
}
