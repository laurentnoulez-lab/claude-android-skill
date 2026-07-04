using System;
using CommunityToolkit.Mvvm.ComponentModel;
using Mao.Domain.Entities;

namespace Mao.App.ViewModels;

/// <summary>Ligne éditable de la grille des postes (poste + son contexte division/chapitre).</summary>
public partial class PosteRowViewModel : ObservableObject
{
    public Poste Poste { get; }
    public Chapitre Chapitre { get; }
    public Division Division { get; }

    /// <summary>Notifie l'éditeur qu'un montant a changé (recalcul des totaux).</summary>
    public event Action? Modifie;

    public PosteRowViewModel(Poste poste, Chapitre chapitre, Division division)
    {
        Poste = poste;
        Chapitre = chapitre;
        Division = division;
    }

    public string DivisionIntitule => Division.Intitule;
    public string ChapitreIntitule => Chapitre.Intitule;

    public string? Code
    {
        get => Poste.CodePosteStd;
        set { Poste.CodePosteStd = value; OnPropertyChanged(); }
    }

    public string Intitule
    {
        get => Poste.Intitule;
        set { Poste.Intitule = value; OnPropertyChanged(); }
    }

    public string Unite
    {
        get => Poste.Unite;
        set { Poste.Unite = value; OnPropertyChanged(); }
    }

    public decimal QuantitePresumee
    {
        get => Poste.QuantitePresumee;
        set
        {
            Poste.QuantitePresumee = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(MontantHtva));
            Modifie?.Invoke();
        }
    }

    public decimal PrixUnitaire
    {
        get => Poste.PrixUnitaire;
        set
        {
            Poste.PrixUnitaire = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(MontantHtva));
            Modifie?.Invoke();
        }
    }

    public decimal MontantHtva => Poste.MontantHtva;
}
