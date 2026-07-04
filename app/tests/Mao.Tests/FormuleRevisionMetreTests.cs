using Mao.Domain.Entities;

namespace Mao.Tests;

public class FormuleRevisionMetreTests
{
    // Formule réelle reprise de MAO.db (dossier 50, formule 3) :
    // « Formule de révision relative aux autres postes » A=0,43 B=0,11 C=0,46.
    private static FormuleRevisionMetre FormuleReelle() => new()
    {
        Numero = 3, Type = "1",
        Intitule = "Formule de révision relative aux autres postes",
        A = 0.43m, B = 0.11m, C = 0.46m,
    };

    [Fact]
    public void Somme_des_coefficients_vaut_un()
    {
        Assert.Equal(1.00m, FormuleReelle().SommeCoefficients);
    }

    [Fact]
    public void Coefficient_un_si_indices_stables()
    {
        var c = FormuleReelle().Coefficient(100m, 100m, 500m, 500m);
        Assert.Equal(1m, c);
    }

    [Fact]
    public void Coefficient_suit_les_indices()
    {
        // salaires +10 %, matériaux +20 % : 0,43×1,1 + 0,11×1,2 + 0,46 = 1,065
        var c = FormuleReelle().Coefficient(110m, 100m, 600m, 500m);
        Assert.Equal(1.065m, c);
    }

    [Fact]
    public void Type_3_sans_revision_donne_un()
    {
        var f = new FormuleRevisionMetre { Type = "3", C = 1m };
        Assert.Equal(1m, f.Coefficient(999m, 1m, 999m, 1m));
    }

    [Fact]
    public void Indice_base_nul_leve_une_erreur()
    {
        Assert.Throws<InvalidOperationException>(() =>
            FormuleReelle().Coefficient(100m, 0m, 500m, 500m));
    }
}
