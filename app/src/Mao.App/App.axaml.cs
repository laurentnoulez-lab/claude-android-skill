using System;
using System.IO;
using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Mao.App.ViewModels;
using Mao.App.Views;
using Mao.Data;

namespace Mao.App;

public partial class App : Application
{
    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var ctx = MaoDbContextFactory.Create(CheminBaseParDefaut());
            var service = new MetreService(ctx);
            desktop.MainWindow = new MainWindow
            {
                DataContext = new MainWindowViewModel(service),
            };
            desktop.Exit += (_, _) => ctx.Dispose();
        }

        base.OnFrameworkInitializationCompleted();
    }

    /// <summary>Base SQLite dans le dossier de données utilisateur (auto-créée).</summary>
    private static string CheminBaseParDefaut()
    {
        var dossier = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "MaoModerne");
        Directory.CreateDirectory(dossier);
        return Path.Combine(dossier, "mao.db");
    }
}
