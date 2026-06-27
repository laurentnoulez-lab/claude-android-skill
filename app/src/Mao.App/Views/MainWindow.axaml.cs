using Avalonia.Controls;
using Avalonia.Input;
using Mao.App.ViewModels;

namespace Mao.App.Views;

public partial class MainWindow : Window
{
    public MainWindow() => InitializeComponent();

    private void OnMetreDoubleTapped(object? sender, TappedEventArgs e)
    {
        if (DataContext is MainWindowViewModel vm && vm.OuvrirCommand.CanExecute(null))
            vm.OuvrirCommand.Execute(null);
    }
}
