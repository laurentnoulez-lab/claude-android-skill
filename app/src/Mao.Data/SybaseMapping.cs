using System.Globalization;
using Mao.Domain.Entities;

namespace Mao.Data;

/// <summary>
/// Conversions et mappages depuis les colonnes Sybase (MAO V8) vers les entités
/// modernes. Isolés de l'accès ODBC pour être testables sans pilote.
/// </summary>
public static class SybaseMapping
{
    /// <summary>Accès à une valeur de colonne par nom (insensible à la casse), null si absente.</summary>
    public delegate object? Colonne(string nom);

    public static string Texte(Colonne col, params string[] noms)
    {
        foreach (var n in noms)
        {
            var v = col(n);
            if (v is not null and not DBNull) return v.ToString()!.Trim();
        }
        return string.Empty;
    }

    public static string? TexteNull(Colonne col, params string[] noms)
    {
        var t = Texte(col, noms);
        return string.IsNullOrEmpty(t) ? null : t;
    }

    public static decimal Decimale(Colonne col, params string[] noms)
    {
        foreach (var n in noms)
        {
            var v = col(n);
            if (v is null or DBNull) continue;
            if (v is decimal d) return d;
            if (v is double db) return (decimal)db;
            if (v is float f) return (decimal)f;
            if (v is int i) return i;
            if (v is long l) return l;
            var s = v.ToString()!.Trim().Replace('.', ',');
            if (decimal.TryParse(s, NumberStyles.Any, CultureInfo.GetCultureInfo("fr-FR"), out var r)) return r;
        }
        return 0m;
    }

    public static decimal? DecimaleNull(Colonne col, params string[] noms)
    {
        foreach (var n in noms)
            if (col(n) is not null and not DBNull) return Decimale(col, n);
        return null;
    }

    public static int Entier(Colonne col, params string[] noms)
    {
        foreach (var n in noms)
        {
            var v = col(n);
            if (v is null or DBNull) continue;
            if (v is int i) return i;
            if (v is long l) return (int)l;
            if (int.TryParse(v.ToString(), out var r)) return r;
        }
        return 0;
    }

    public static int? EntierNull(Colonne col, params string[] noms)
    {
        foreach (var n in noms)
            if (col(n) is not null and not DBNull) return Entier(col, n);
        return null;
    }

    /// <summary>Booléen depuis un drapeau Sybase 'O'/'N' (ou 1/0, vrai/faux).</summary>
    public static bool Booleen(Colonne col, params string[] noms)
    {
        var t = Texte(col, noms).ToUpperInvariant();
        return t is "O" or "1" or "OUI" or "Y" or "TRUE";
    }

    /// <summary>Mappe une ligne POSTE_STD du catalogue Sybase vers <see cref="PosteStd"/>.</summary>
    public static PosteStd MapPosteStd(Colonne col) => new()
    {
        Code = Texte(col, "C_POSTE_METRE_STD"),
        ListeStandardisee = Texte(col, "C_LISTE_STANDARDISEE"),
        ChapitreStdId = Entier(col, "ID_CHAPITRE_STANDARDISE"),
        PosteStdId = Entier(col, "ID_POSTE_STANDARDISE"),
        Intitule = Texte(col, "L_INTITULE"),
        Description = TexteNull(col, "TX_DESCRIPTION"),
        Liste = TexteNull(col, "L_LISTE"),
        Unite = Texte(col, "C_UNITES"),
        TypePrix = Texte(col, "TY_PRIX_POSTE"),
        FormuleRefId = EntierNull(col, "FREF_ID"),
        CoefConvMin = Decimale(col, "N_CCONV_MIN"),
        CoefConvMax = Decimale(col, "N_CCONV_MAX"),
        CoefConvPropose = Decimale(col, "N_CCONV_PROPOSE"),
        TypeDechetId = EntierNull(col, "TYDE_ID"),
        SupprimeRw03 = Booleen(col, "O_SUP_RW03"),
        InfoPosteId = EntierNull(col, "INPO_ID"),
        RefCctRw99 = TexteNull(col, "L_REF_CCTRW99"),
        RefCsc = TexteNull(col, "L_REF_CSC"),
        Cautionnement = Booleen(col, "O_CAUTIONNEMENT"),
        ReductionApplicable = Booleen(col, "O_REDUCTION_APPLICABLE"),
        PrixUnitaireSuggere = DecimaleNull(col, "M_PRIX_UNITAIRE"),
        ParentCode = TexteNull(col, "PARENT"),
        NbModifRw03 = TexteNull(col, "C_NMODIF_RW03"),
    };

    /// <summary>Mappe une ligne METRE Sybase vers <see cref="Metre"/> (sans la hiérarchie).</summary>
    public static Metre MapMetre(Colonne col) => new()
    {
        Intitule = Texte(col, "L_INTITULE"),
        CodeCct = TexteNull(col, "C_CCT"),
        TvaIdentique = Booleen(col, "O_TVA_IDENTIQUE"),
        TauxTvaCode = TexteNull(col, "C_TAUX_TVA"),
        ListeNormalisee = Texte(col, "C_LISTE_STANDARDISEE", "LISTE_NORM") is { Length: > 0 } l ? l : "RW99",
    };
}
