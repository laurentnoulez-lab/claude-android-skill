using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;

namespace Mao.App.Views;

/// <summary>Petites boîtes de dialogue (information simple).</summary>
public static class Dialogs
{
    public static Task Info(Window parent, string titre, string message)
    {
        var ok = new Button { Content = "OK", HorizontalAlignment = HorizontalAlignment.Right, MinWidth = 90 };
        var fenetre = new Window
        {
            Title = titre,
            Width = 460,
            SizeToContent = SizeToContent.Height,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            CanResize = false,
            Content = new StackPanel
            {
                Margin = new Avalonia.Thickness(16),
                Spacing = 16,
                Children =
                {
                    new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap },
                    ok,
                },
            },
        };
        ok.Click += (_, _) => fenetre.Close();
        return fenetre.ShowDialog(parent);
    }
}
