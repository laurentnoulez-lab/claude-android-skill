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

    public MaoDbContext(DbContextOptions<MaoDbContext> options) : base(options) { }

    protected override void OnModelCreating(ModelBuilder b)
    {
        b.Entity<Metre>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Intitule).IsRequired();
            e.HasMany(x => x.Divisions).WithOne(d => d.Metre!)
                .HasForeignKey(d => d.MetreId).OnDelete(DeleteBehavior.Cascade);
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
    }
}
