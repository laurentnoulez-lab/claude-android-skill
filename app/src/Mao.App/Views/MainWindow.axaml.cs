using System.ComponentModel;
using System.IO;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
using Mao.App.ViewModels;
using Mao.Domain.Entities;
using Mao.Reports;

namespace Mao.App.Views;

public partial class MainWindow : Window
{
    private MainWindowViewModel? _vm;
    private MetreEditorViewModel? _editeurSuivi;

    public MainWindow()
    {
        InitializeComponent();
        DataContextChanged += OnDataContextChanged;
    }

    private void OnDataContextChanged(object? sender, System.EventArgs e)
    {
        if (_vm is not null) _vm.PropertyChanged -= OnVmPropertyChanged;
        _vm = DataContext as MainWindowViewModel;
        if (_vm is not null) _vm.PropertyChanged += OnVmPropertyChanged;
        SuivreEditeur();
    }

    private void OnVmPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(MainWindowViewModel.Editeur))
            SuivreEditeur();
    }

    private void SuivreEditeur()
    {
        if (_editeurSuivi is not null)
        {
            _editeurSuivi.CatalogueDemande -= OnCatalogueDemande;
            _editeurSuivi.ExportDemande -= OnExportDemande;
        }
        _editeurSuivi = _vm?.Editeur;
        if (_editeurSuivi is not null)
        {
            _editeurSuivi.CatalogueDemande += OnCatalogueDemande;
            _editeurSuivi.ExportDemande += OnExportDemande;
        }
    }

    private async void OnExportDemande(TypeDocument type, string format)
    {
        if (_editeurSuivi is null) return;

        var estPdf = format == "pdf";
        var ext = estPdf ? "pdf" : "csv";
        var nomDefaut = $"{_editeurSuivi.Metre.Intitule}_{type}".Replace(' ', '_') + "." + ext;

        var fichier = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
        {
            Title = "Enregistrer l'état",
            SuggestedFileName = nomDefaut,
            DefaultExtension = ext,
            FileTypeChoices = new[]
            {
                new FilePickerFileType(estPdf ? "Document PDF" : "Fichier CSV")
                {
                    Patterns = new[] { "*." + ext },
                },
            },
        });
        if (fichier is null) return;

        var chemin = fichier.TryGetLocalPath() ?? fichier.Path.LocalPath;
        var doc = _editeurSuivi.GenererDocument(type);
        await Task.Run(() =>
        {
            if (estPdf) PdfExporter.Exporter(doc, chemin);
            else CsvExporter.Exporter(doc, chemin);
        });
    }

    private async void OnCatalogueDemande()
    {
        if (_vm is null || _editeurSuivi is null) return;
        var dlg = new CatalogueWindow { DataContext = new CatalogueViewModel(_vm.Catalogue) };
        var choix = await dlg.ShowDialog<PosteStd?>(this);
        if (choix is not null)
            _editeurSuivi.InsererPosteNormalise(choix);
    }

    private void OnMetreDoubleTapped(object? sender, TappedEventArgs e)
    {
        if (DataContext is MainWindowViewModel vm && vm.OuvrirCommand.CanExecute(null))
            vm.OuvrirCommand.Execute(null);
    }

    private void OnOuvrirAdmin(object? sender, RoutedEventArgs e)
    {
        if (_vm is null) return;
        new AdminWindow { DataContext = new AdminViewModel(_vm.Admin) }.ShowDialog(this);
    }

    private void OnOuvrirRevision(object? sender, RoutedEventArgs e)
    {
        if (_vm is null) return;
        new RevisionWindow { DataContext = new RevisionViewModel(_vm.Revision) }.ShowDialog(this);
    }

    private void OnOuvrirStatistiques(object? sender, RoutedEventArgs e)
    {
        if (_vm is null) return;
        new AdjudicationWindow { DataContext = new AdjudicationViewModel(_vm.Statistiques) }.ShowDialog(this);
    }

    // --- Menu Données ---

    private async void OnImporterMaoDb(object? sender, RoutedEventArgs e)
    {
        if (_vm is null) return;
        var chemin = await ChoisirOuverture("Importer un fichier MAO (.db)", "Base MAO (Sybase)", "*.db");
        if (chemin is null) return;
        var rapport = await Task.Run(() => _vm.Donnees.ImporterMaoDb(chemin));
        if (rapport.Succes) _vm.RafraichirApresImport();
        await Dialogs.Info(this, "Import MAO.db", rapport.Message);
    }

    private async void OnImporterCatalogue(object? sender, RoutedEventArgs e)
    {
        if (_vm is null) return;
        var chemin = await ChoisirOuverture("Importer le catalogue (JSON)", "Catalogue JSON", "*.json");
        if (chemin is null) return;
        var n = await Task.Run(() => _vm.Donnees.ImporterCatalogueJson(chemin));
        await Dialogs.Info(this, "Import catalogue", $"{n} poste(s) importé(s) / mis à jour.");
    }

    private async void OnExporterSauvegarde(object? sender, RoutedEventArgs e)
    {
        if (_vm is null) return;
        var f = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
        {
            Title = "Exporter une sauvegarde",
            SuggestedFileName = "sauvegarde_mao.json",
            DefaultExtension = "json",
            FileTypeChoices = new[] { new FilePickerFileType("Sauvegarde JSON") { Patterns = new[] { "*.json" } } },
        });
        if (f is null) return;
        var chemin = f.TryGetLocalPath() ?? f.Path.LocalPath;
        await Task.Run(() => _vm.Donnees.ExporterSauvegarde(chemin));
        await Dialogs.Info(this, "Sauvegarde", "Export terminé : " + chemin);
    }

    private async void OnImporterSauvegarde(object? sender, RoutedEventArgs e)
    {
        if (_vm is null) return;
        var chemin = await ChoisirOuverture("Importer une sauvegarde (JSON)", "Sauvegarde JSON", "*.json");
        if (chemin is null) return;
        await Task.Run(() => _vm.Donnees.ImporterSauvegarde(chemin));
        _vm.RafraichirApresImport();
        await Dialogs.Info(this, "Sauvegarde", "Import terminé.");
    }

    private async Task<string?> ChoisirOuverture(string titre, string nomType, string motif)
    {
        var fichiers = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = titre,
            AllowMultiple = false,
            FileTypeFilter = new[]
            {
                new FilePickerFileType(nomType) { Patterns = new[] { motif } },
                new FilePickerFileType("Tous les fichiers") { Patterns = new[] { "*.*" } },
            },
        });
        if (fichiers.Count == 0) return null;
        return fichiers[0].TryGetLocalPath() ?? fichiers[0].Path.LocalPath;
    }
}
