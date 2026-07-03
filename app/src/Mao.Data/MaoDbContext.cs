using Mao.Domain.Entities;
using Microsoft.EntityFrameworkCore;

namespace Mao.Data;

/// <summary>
/// Contexte EF Core sur SQLite. Remplace la base Sybase SQL Anywhere 6
/// (MAO.db) par une base embarquée mono-fichier.
/// </summary>
public class MaoDbContext : DbContext
{
    public DbSet<Metre> Metres => Set<Metre>();
    public DbSet<Division> Divisions => Set<Division>();
    public DbSet<Chapitre> Chapitres => Set<Chapitre>();
    public DbSet<Poste> Postes => Set<Poste>();
    public DbSet<Tva> TauxTva => Set<Tva>();
    public DbSet<PosteStd> PostesStd => Set<PosteStd>();

    // Révision de prix
    public DbSet<Indice> Indices => Set<Indice>();
    public DbSet<FormuleReference> FormulesReference => Set<FormuleReference>();
    public DbSet<FormuleTerme> FormuleTermes => Set<FormuleTerme>();

    // Administration
    public DbSet<Utilisateur> Utilisateurs => Set<Utilisateur>();
    public DbSet<EntiteAdmin> Entites => Set<EntiteAdmin>();
    public DbSet<AgentAdmin> Agents => Set<AgentAdmin>();
    public DbSet<Parametre> Parametres => Set<Parametre>();

    // Adjudications & statistiques
    public DbSet<Adjudication> Adjudications => Set<Adjudication>();
    public DbSet<StatistiquePrix> StatistiquesPrix => Set<StatistiquePrix>();

    // Déchets & révision par métré
    public DbSet<TypeDechet> TypesDechets => Set<TypeDechet>();
    public DbSet<CodeDechet> CodesDechets => Set<CodeDechet>();
    public DbSet<PrixPosteDechet> PrixPostesDechets => Set<PrixPosteDechet>();
    public DbSet<FormuleRevisionMetre> FormulesRevisionMetre => Set<FormuleRevisionMetre>();

    public MaoDbContext(DbContextOptions<MaoDbContext> options) : base(options) { }

    protected override void OnModelCreating(ModelBuilder b)
    {
        b.Entity<Metre>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Intitule).IsRequired();
            e.HasMany(x => x.Divisions).WithOne(d => d.Metre!)
                .HasForeignKey(d => d.MetreId).OnDelete(DeleteBehavior.Cascade);
            e.HasMany(x => x.FormulesRevision).WithOne()
                .HasForeignKey(f => f.MetreId).OnDelete(DeleteBehavior.Cascade);
            e.HasMany(x => x.PrixDechets).WithOne()
                .HasForeignKey(p => p.MetreId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<TypeDechet>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).ValueGeneratedNever();
        });

        b.Entity<CodeDechet>(e =>
        {
            e.HasKey(x => x.Id);
            e.HasIndex(x => x.TypeDechetId);
            e.Property(x => x.Pourcentage).HasColumnType("decimal(5,2)");
        });

        b.Entity<PrixPosteDechet>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Prix).HasColumnType("decimal(16,4)");
        });

        b.Entity<FormuleRevisionMetre>(e =>
        {
            e.HasKey(x => x.Id);
            e.Ignore(x => x.SommeCoefficients);
            foreach (var col in new[] { "A", "A1", "A2", "B", "B1", "B2", "C", "C2", "D" })
                e.Property(col).HasColumnType("decimal(12,5)");
        });

        b.Entity<Division>(e =>
        {
            e.HasKey(x => x.Id);
            e.HasMany(x => x.Chapitres).WithOne(c => c.Division!)
                .HasForeignKey(c => c.DivisionId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<Chapitre>(e =>
        {
            e.HasKey(x => x.Id);
            e.HasMany(x => x.Postes).WithOne(p => p.Chapitre!)
                .HasForeignKey(p => p.ChapitreId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<Poste>(e =>
        {
            e.HasKey(x => x.Id);
            e.Ignore(x => x.MontantHtva);
            e.Property(x => x.QuantitePresumee).HasColumnType("decimal(18,4)");
            e.Property(x => x.PrixUnitaire).HasColumnType("decimal(18,4)");
        });

        b.Entity<Tva>(e =>
        {
            e.HasKey(x => x.Code);
            e.Property(x => x.Taux).HasColumnType("decimal(6,4)");
        });

        b.Entity<PosteStd>(e =>
        {
            e.HasKey(x => x.Code);
            e.Property(x => x.CoefConvMin).HasColumnType("decimal(18,4)");
            e.Property(x => x.CoefConvMax).HasColumnType("decimal(18,4)");
            e.Property(x => x.CoefConvPropose).HasColumnType("decimal(18,4)");
        });

        b.Entity<Indice>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Valeur).HasColumnType("decimal(18,4)");
            e.HasIndex(x => new { x.Type, x.Code, x.Periode }).IsUnique();
        });

        b.Entity<FormuleReference>(e =>
        {
            e.HasKey(x => x.Id);
            e.Ignore(x => x.SommeCoefficients);
            e.Ignore(x => x.EstCoherente);
            e.Property(x => x.PartFixe).HasColumnType("decimal(8,5)");
            e.HasMany(x => x.Termes).WithOne(t => t.FormuleReference!)
                .HasForeignKey(t => t.FormuleRefId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<FormuleTerme>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Coefficient).HasColumnType("decimal(8,5)");
        });

        b.Entity<Utilisateur>(e => e.HasKey(x => x.Id));
        b.Entity<EntiteAdmin>(e =>
        {
            e.HasKey(x => x.Id);
            e.HasMany(x => x.Agents).WithOne(a => a.Entite!)
                .HasForeignKey(a => a.EntiteId).OnDelete(DeleteBehavior.Cascade);
        });
        b.Entity<AgentAdmin>(e => e.HasKey(x => x.Id));
        b.Entity<Parametre>(e => e.HasKey(x => x.Cle));

        b.Entity<Adjudication>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Montant).HasColumnType("decimal(18,4)");
        });

        b.Entity<StatistiquePrix>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Quantite).HasColumnType("decimal(18,4)");
            e.Property(x => x.PrixMin).HasColumnType("decimal(18,4)");
            e.Property(x => x.PrixMax).HasColumnType("decimal(18,4)");
            e.HasIndex(x => new { x.ChapitreStdId, x.PosteStdId });
            e.HasIndex(x => x.CodePosteStd);
        });
    }
}
