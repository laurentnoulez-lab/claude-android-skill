using Microsoft.EntityFrameworkCore;

namespace Mao.Data;

/// <summary>Fabrique de contexte : crée la base SQLite si absente et applique le seed.</summary>
public static class MaoDbContextFactory
{
    /// <summary>Crée un contexte pointant sur le fichier SQLite indiqué (créé au besoin).</summary>
    public static MaoDbContext Create(string cheminFichier)
    {
        var options = new DbContextOptionsBuilder<MaoDbContext>()
            .UseSqlite($"Data Source={cheminFichier}")
            .Options;
        var ctx = new MaoDbContext(options);
        ctx.Database.EnsureCreated();
        Seed.Appliquer(ctx);
        CatalogueSeed.Appliquer(ctx);
        DonneesSeed.Appliquer(ctx);
        return ctx;
    }
}
