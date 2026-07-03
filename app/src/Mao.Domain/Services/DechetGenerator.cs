using Mao.Domain.Entities;

namespace Mao.Domain.Services;

/// <summary>Résultat de la génération des postes déchets.</summary>
public record RapportGenerationDechets(int PostesGeneres, int PostesSources, string Message);

/// <summary>
/// Reproduit la « Génération des postes D9000 » de MAO V8 :
/// 1. chaque poste du métré porte un type de déchet (TYPE_DECHET_POSTE) et un
///    coefficient de conversion → quantité de déchet = quantité × coefficient ;
/// 2. la table CODE_DECHET répartit chaque type de déchet vers ses codes de
///    destination D9xxx avec un pourcentage par défaut ;
/// 3. un poste est créé par code D9xxx utilisé, dans un chapitre dédié, avec le
///    prix du métré (PRIX_POSTE_DECHET) s'il existe.
/// Les postes générés sont marqués <see cref="Poste.EstGenere"/> et remplacés
/// à chaque exécution (comme O_GEN_AUTO dans MAO V8).
/// </summary>
public class DechetGenerator
{
    public const string IntituleChapitre = "Postes déchets (génération D9000)";

    private readonly IReadOnlyList<CodeDechet> _codesDechets;
    private readonly IReadOnlyDictionary<string, PosteStd> _catalogue;

    public DechetGenerator(IReadOnlyList<CodeDechet> codesDechets,
                           IReadOnlyDictionary<string, PosteStd> catalogue)
    {
        _codesDechets = codesDechets;
        _catalogue = catalogue;
    }

    public RapportGenerationDechets Generer(Metre metre)
    {
        // 1. Quantités de déchet par type, à partir des postes non générés.
        var parType = new Dictionary<int, decimal>();
        int sources = 0;
        foreach (var poste in metre.Divisions.SelectMany(d => d.Chapitres).SelectMany(c => c.Postes))
        {
            if (poste.EstGenere || poste.TypeDechetId is not int type || type == 0) continue;
            var coef = poste.CoefConversionDechet ?? 1m;
            parType[type] = parType.GetValueOrDefault(type) + poste.QuantitePresumee * coef;
            sources++;
        }

        // 2. Répartition vers les codes D9xxx (pourcentages par défaut de CODE_DECHET).
        var parCode = new Dictionary<string, decimal>();
        foreach (var (type, quantite) in parType)
        {
            foreach (var cd in _codesDechets.Where(c => c.TypeDechetId == type))
            {
                var part = quantite * (cd.Pourcentage ?? 0m) / 100m;
                if (part <= 0m) continue;
                parCode[cd.Code] = parCode.GetValueOrDefault(cd.Code) + part;
            }
        }

        // 3. Remplacement des postes générés dans le chapitre dédié.
        var prixMetre = metre.PrixDechets.ToDictionary(p => p.CodePosteStd, p => p.Prix);
        var chapitre = ChapitreGeneration(metre);
        chapitre.Postes.RemoveAll(p => p.EstGenere);

        int numero = 1;
        foreach (var (code, quantite) in parCode.OrderBy(kv => kv.Key))
        {
            _catalogue.TryGetValue(code, out var std);
            chapitre.Postes.Add(new Poste
            {
                ChapitreId = chapitre.Id,
                Numero = numero++,
                CodePosteStd = code,
                Intitule = std?.Intitule ?? code,
                Description = std?.Description,
                Unite = std?.Unite ?? "t",
                TypePrix = std?.TypePrix ?? "QP",
                QuantitePresumee = Math.Round(quantite, 3, MidpointRounding.AwayFromZero),
                PrixUnitaire = prixMetre.GetValueOrDefault(code, std?.PrixUnitaireSuggere ?? 0m),
                EstNormalise = std is not null,
                EstGenere = true,
                TauxTvaCode = metre.TvaIdentique ? null : metre.TauxTvaCode,
            });
        }

        return new RapportGenerationDechets(parCode.Count, sources,
            parCode.Count == 0
                ? "Aucun poste du métré ne porte de type de déchet : rien à générer."
                : $"{parCode.Count} poste(s) déchets générés à partir de {sources} poste(s) source.");
    }

    /// <summary>Chapitre dédié aux postes générés (créé au besoin dans la dernière division).</summary>
    private static Chapitre ChapitreGeneration(Metre metre)
    {
        var existant = metre.Divisions.SelectMany(d => d.Chapitres)
            .FirstOrDefault(c => c.Intitule == IntituleChapitre);
        if (existant is not null) return existant;

        var division = metre.Divisions.OrderBy(d => d.Numero).LastOrDefault();
        if (division is null)
        {
            division = new Division { MetreId = metre.Id, Numero = 1, Intitule = "Division 1" };
            metre.Divisions.Add(division);
        }
        var numero = (division.Chapitres.Count == 0 ? 0 : division.Chapitres.Max(c => c.Numero)) + 1;
        var chapitre = new Chapitre { DivisionId = division.Id, Numero = numero, Intitule = IntituleChapitre };
        division.Chapitres.Add(chapitre);
        return chapitre;
    }
}
