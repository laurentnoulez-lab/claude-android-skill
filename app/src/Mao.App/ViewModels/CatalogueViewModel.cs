using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Mao.Data;
using Mao.Domain.Entities;

namespace Mao.App.ViewModels;

/// <summary>Recherche/sélection d'un poste dans le catalogue normalisé.</summary>
public partial class CatalogueViewModel : ObservableObject
{
    private readonly CatalogueService _service;

    public ObservableCollection<PosteStd> Resultats { get; } = new();

    [ObservableProperty] private string _terme = string.Empty;
    [ObservableProperty] private PosteStd? _selection;

    public CatalogueViewModel(CatalogueService service)
    {
        _service = service;
        Rechercher();
    }

    partial void OnTermeChanged(string value) => Rechercher();

    [RelayCommand]
    private void Rechercher()
    {
        Resultats.Clear();
        foreach (var p in _service.Rechercher(Terme))
            Resultats.Add(p);
        Selection ??= Resultats.FirstOrDefault();
    }
}
