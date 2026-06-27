using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Mao.Data;
using Mao.Domain.Entities;

namespace Mao.App.ViewModels;

/// <summary>Administration : utilisateurs, entités, agents, TVA, paramètres.</summary>
public partial class AdminViewModel : ObservableObject
{
    private readonly AdminService _service;

    public ObservableCollection<Utilisateur> Utilisateurs { get; } = new();
    public ObservableCollection<EntiteAdmin> Entites { get; } = new();
    public ObservableCollection<Tva> TauxTva { get; } = new();
    public ObservableCollection<Parametre> Parametres { get; } = new();

    [ObservableProperty] private Utilisateur? _utilisateurSelectionne;
    [ObservableProperty] private EntiteAdmin? _entiteSelectionnee;
    [ObservableProperty] private Tva? _tvaSelectionnee;

    public AdminViewModel(AdminService service)
    {
        _service = service;
        Recharger();
    }

    private void Recharger()
    {
        Remplir(Utilisateurs, _service.ListerUtilisateurs());
        Remplir(Entites, _service.ListerEntites());
        Remplir(TauxTva, _service.ListerTva());
        Remplir(Parametres, _service.ListerParametres());
    }

    private static void Remplir<T>(ObservableCollection<T> c, IEnumerable<T> items)
    {
        c.Clear();
        foreach (var i in items) c.Add(i);
    }

    [RelayCommand]
    private void AjouterUtilisateur()
    {
        var u = _service.EnregistrerUtilisateur(new Utilisateur { Code = "NOUVEAU", Nom = "Nouvel utilisateur" });
        Utilisateurs.Add(u);
    }

    [RelayCommand]
    private void SupprimerUtilisateur()
    {
        if (UtilisateurSelectionne is null) return;
        _service.SupprimerUtilisateur(UtilisateurSelectionne.Id);
        Utilisateurs.Remove(UtilisateurSelectionne);
    }

    [RelayCommand]
    private void AjouterEntite()
    {
        var e = _service.EnregistrerEntite(new EntiteAdmin { Code = "ENT", Nom = "Nouvelle entité" });
        Entites.Add(e);
    }

    [RelayCommand]
    private void SupprimerEntite()
    {
        if (EntiteSelectionnee is null) return;
        _service.SupprimerEntite(EntiteSelectionnee.Id);
        Entites.Remove(EntiteSelectionnee);
    }

    [RelayCommand]
    private void AjouterTva()
    {
        var t = new Tva { Code = "NEW", Taux = 0m, Libelle = "Nouveau taux" };
        _service.EnregistrerTva(t);
        TauxTva.Add(t);
    }

    [RelayCommand]
    private void SupprimerTva()
    {
        if (TvaSelectionnee is null) return;
        _service.SupprimerTva(TvaSelectionnee.Code);
        TauxTva.Remove(TvaSelectionnee);
    }

    [RelayCommand]
    private void Enregistrer() => _service.Sauvegarder();
}
