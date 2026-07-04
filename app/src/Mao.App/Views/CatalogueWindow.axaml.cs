using Avalonia.Controls;
using Avalonia.Interactivity;
using Mao.App.ViewModels;
using Mao.Domain.Entities;

namespace Mao.App.Views;

public partial class CatalogueWindow : Window
{
    public CatalogueWindow() => InitializeComponent();

    private void OnInserer(object? sender, RoutedEventArgs e)
    {
        if (DataContext is CatalogueViewModel vm && vm.Selection is PosteStd p)
            Close(p);
    }

    private void OnAnnuler(object? sender, RoutedEventArgs e) => Close(null);
}
