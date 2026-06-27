using System.ComponentModel;
using System.IO;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Input;
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
}
