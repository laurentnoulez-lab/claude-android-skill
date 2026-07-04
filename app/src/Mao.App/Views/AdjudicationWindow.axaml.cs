using System.IO;
using Avalonia.Controls;
using Avalonia.Platform.Storage;
using Mao.App.ViewModels;

namespace Mao.App.Views;

public partial class AdjudicationWindow : Window
{
    private AdjudicationViewModel? _vm;

    public AdjudicationWindow()
    {
        InitializeComponent();
        DataContextChanged += (_, _) =>
        {
            if (_vm is not null)
            {
                _vm.ImportDemande -= OnImport;
                _vm.ExportDemande -= OnExport;
            }
            _vm = DataContext as AdjudicationViewModel;
            if (_vm is not null)
            {
                _vm.ImportDemande += OnImport;
                _vm.ExportDemande += OnExport;
            }
        };
    }

    private async void OnImport()
    {
        if (_vm is null) return;
        var fichiers = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "Importer un fichier statistiques MAO",
            AllowMultiple = false,
            FileTypeFilter = new[]
            {
                new FilePickerFileType("Fichiers statistiques / texte") { Patterns = new[] { "*.txt", "*.sta", "*.*" } },
            },
        });
        var f = fichiers.Count > 0 ? fichiers[0] : null;
        if (f is null) return;
        var chemin = f.TryGetLocalPath() ?? f.Path.LocalPath;
        _vm.ImporterFichier(chemin);
    }

    private async void OnExport()
    {
        if (_vm is null) return;
        var f = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
        {
            Title = "Exporter les statistiques",
            SuggestedFileName = "statistiques_prix.csv",
            DefaultExtension = "csv",
            FileTypeChoices = new[] { new FilePickerFileType("Fichier CSV") { Patterns = new[] { "*.csv" } } },
        });
        if (f is null) return;
        var chemin = f.TryGetLocalPath() ?? f.Path.LocalPath;
        _vm.ExporterCsv(chemin);
    }
}
