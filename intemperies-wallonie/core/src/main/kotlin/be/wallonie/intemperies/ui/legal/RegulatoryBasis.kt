package be.wallonie.intemperies.ui.legal

/**
 * Base réglementaire d'un « jour d'intempérie ».
 *
 * Les extraits [Excerpt.quote] sont recopiés mot à mot du CCT Qualiroutes, Chapitre A
 * « Clauses administratives », édition du 01/01/2026 (pages indiquées), lui-même reproduisant
 * l'AR du 14 janvier 2013 (RGE). L'art. 76, § 4 a été recoupé avec la consolidation officieuse
 * WALLEX du RGE (version en vigueur depuis le 01/01/2025).
 * Les [Note] sont des interprétations : elles sont présentées comme telles dans l'application.
 */
object RegulatoryBasis {
    const val SOURCE_DOC = "CCT Qualiroutes — Chapitre A « Clauses administratives », édition du 01/01/2026"
    const val SHORT_REF = "CCT Qualiroutes, ch. A (éd. 01/01/2026), art. 76 § 4, 4° — AR 14/01/2013 (RGE)"

    data class Excerpt(val title: String, val reference: String, val quote: String)
    data class Note(val title: String, val text: String)

    val excerpts = listOf(
        Excerpt(
            title = "Jours non ouvrables — conditions météorologiques défavorables",
            reference = "Art. 76, § 4 (RGE) — Chapitre A, p. A.77",
            quote = "§ 4. Quand le délai d’exécution est fixé en jours ouvrables, ne sont pas considérés comme tels:\n" +
                "1° les samedis, sauf ceux pendant lesquels l’entrepreneur a travaillé ou aurait dû travailler en raison de " +
                "la répartition du temps de travail sur le chantier;\n" +
                "2° les dimanches et jours fériés légaux;\n" +
                "3° les jours de vacances annuelles payées et les jours de repos compensatoire prévus par un arrêté " +
                "royal ou dans une convention collective de travail rendue obligatoire par arrêté royal;\n" +
                "4° les jours pendant lesquels, sur reconnaissance de l'adjudicateur, le travail a, ou aurait, par suite de " +
                "conditions météorologiques défavorables ou de leurs conséquences, été rendu impossible pendant " +
                "quatre heures au moins.",
        ),
        Excerpt(
            title = "Délai non fixé en jours ouvrables",
            reference = "Art. 76, § 4, alinéa 2 (RGE) — Chapitre A, p. A.77",
            quote = "Toutefois, si pour des raisons économiques, le délai d’exécution du marché n’est pas fixé en jours " +
                "ouvrables mais en jours, en semaines, mois ou années ou de date à date ou pour une date finale " +
                "déterminée, tous les jours indistinctement sont comptés dans le délai.",
        ),
        Excerpt(
            title = "Marchés d’électromécanique (complément Qualiroutes)",
            reference = "Art. 76, § 4, 4° complété — Chapitre A, p. A.77",
            quote = "L’adjudicataire est tenu d’introduire hebdomadairement une liste des jours pendant lesquels le " +
                "travail a, par suite de conditions météorologiques défavorables ou de leurs conséquences, été " +
                "rendu impossible pendant 4 heures consécutives au moins. Cette liste doit parvenir à " +
                "l'adjudicateur, sous peine de non-recevabilité, au plus tard le troisième jour ouvrable de la " +
                "semaine suivante.\nA défaut de réponse de l'adjudicateur dans les 3 jours ouvrables, la liste est approuvée.",
        ),
        Excerpt(
            title = "Journal des travaux",
            reference = "Art. 83, § 1er, 1° (RGE) — Chapitre A, p. A.87",
            quote = "Il y inscrit jour par jour, notamment, les renseignements ci-après:\n" +
                "1° l’indication des conditions atmosphériques, des interruptions de travaux pour cause de conditions " +
                "météorologiques défavorables, des heures de travail, […]",
        ),
        Excerpt(
            title = "Marchés de génie civil (complément Qualiroutes)",
            reference = "Art. 83, § 2, alinéa 1 remplacé — Chapitre A, p. A.87",
            quote = "La tenue d'un journal des travaux est obligatoire. L'adjudicateur peut décider de ne pas remplir " +
                "celui-ci jour par jour. Il peut décider de couvrir une période de plusieurs jours en cas d’inactivité.",
        ),
        Excerpt(
            title = "Révision des prix",
            reference = "Art. 38/7, marchés de génie civil (définition de jok) — Chapitre A, p. A.54",
            quote = "Les jours pendant lesquels les travaux ou les fournitures sont suspendus (congés, intempéries, " +
                "suspensions motivées…) par ordre de service, ne sont pas compris dans jok.",
        ),
    )

    val notes = listOf(
        Note(
            "Critère réglementaire ≠ critère pluviométrique",
            "Le texte ne fixe aucun seuil de pluie : il vise l’impossibilité de travailler pendant au moins 4 heures, " +
                "du fait des conditions météorologiques défavorables « ou de leurs conséquences », et sur reconnaissance " +
                "de l’adjudicateur. Les critères de l’application (durée de pluie, température) sont des indicateurs " +
                "objectifs pour documenter cette reconnaissance ; ils ne la remplacent pas.",
        ),
        Note(
            "« Quatre heures au moins »",
            "Le RGE vise une durée d’au moins 4 h, consécutives ou non ; le complément électromécanique exige 4 h " +
                "consécutives. Le critère par défaut de l’application (« plus de 4 h », comparaison stricte) est donc plus " +
                "exigeant que le texte : désactivez « Comparaison stricte » pour retenir les journées à 4 h exactement.",
        ),
        Note(
            "Conséquences des intempéries",
            "Un sol détrempé, gelé ou enneigé peut rendre le travail impossible sans pluie pendant la plage horaire. " +
                "L’application ne mesure que la pluie et la température : ces situations relèvent du constat au journal des travaux.",
        ),
        Note(
            "Jours déjà non ouvrables",
            "Samedis, dimanches et jours fériés légaux (1° et 2°) ne sont de toute façon pas des jours ouvrables : " +
                "l’application les signale, et les exports PDF et Excel comptent à part les intempéries « ouvrables ». Les congés annuels et " +
                "repos compensatoires du secteur (3°) ne sont pas intégrés.",
        ),
        Note(
            "Champ d’application",
            "Ces règles ne jouent que si le délai d’exécution est fixé en jours ouvrables. Vérifiez aussi les " +
                "éventuelles dérogations du cahier spécial des charges.",
        ),
    )

    const val VERIFY = "Extraits recopiés de l’édition du 01/01/2026 du Chapitre A. Avant usage contractuel, vérifier " +
        "l’édition en vigueur sur qc.spw.wallonie.be (Qualiroutes) et la consolidation du RGE sur wallex.wallonie.be."
}
