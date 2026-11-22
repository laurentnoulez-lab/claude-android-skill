package be.wallonie.intemperies.ui.legal

import be.wallonie.intemperies.model.Criteria
import be.wallonie.intemperies.ui.Fmt

/**
 * Mode de fonctionnement, rédigé avec les valeurs de paramètres EN VIGUEUR
 * (affiché dans l'application et repris dans le classeur Excel).
 */
object HowItWorks {
    data class Section(val title: String, val lines: List<String>)

    fun sections(c: Criteria, retentionMonths: Int?): List<Section> {
        val op = if (c.strictComparison) "plus de" else "au moins"
        val on = "✔ provoque une intempérie"
        val off = "✘ signalé seulement (n'influence pas la couleur)"
        return listOf(
            Section(
                "Principe",
                listOf(
                    "Pour chaque commune suivie, l'application qualifie chaque journée à partir des mesures du réseau " +
                        "hydrométrique du SPW, sur la plage de travail ${c.windowStart}–${c.windowEnd}.",
                    "Une journée est une INTEMPÉRIE si les précipitations (pluie, neige ou grêle) OU la température " +
                        "atteignent les seuils activés ci-dessous. Tous les seuils se modifient dans Paramètres ; " +
                        "les calendriers, PDF et Excel sont recalculés immédiatement.",
                ),
            ),
            Section(
                "1. Stations de mesure",
                listOf(
                    "Chaque commune est localisée par son centroïde. L'application retient automatiquement le pluviomètre " +
                        "SPW le plus proche (mesures toutes les 5 minutes).",
                    "Pour la température, elle retient la station SPW de température de l'air la plus proche à moins de " +
                        "${Fmt.num(c.maxTemperatureDistanceKm, 0)} km (moyennes horaires). Au-delà, la température est « non disponible ».",
                    "Les stations retenues et leur distance sont affichées sur le calendrier, où elles peuvent être changées manuellement.",
                ),
            ),
            Section(
                "2. Collecte des données",
                listOf(
                    "Synchronisation automatique toutes les heures (connexion requise), uniquement pour les stations utilisées ; " +
                        "seules les données manquantes sont téléchargées.",
                    "Une fois par jour, les 35 derniers jours sont rechargés pour intégrer les corrections et validations du SPW. " +
                        "Une journée marquée * contient des précipitations pas encore validées (code qualité SPW ≥ 200).",
                    if (retentionMonths != null) "Historique conservé : mois en cours + $retentionMonths mois précédents ; le reste est supprimé automatiquement."
                    else "Historique conservé : paramétrable (mois en cours + N mois précédents).",
                ),
            ),
            Section(
                "3. Précipitations (pluie, neige, grêle)",
                listOf(
                    "Les pluviomètres SPW sont des pluviomètres à pesée : ils pèsent toutes les précipitations recueillies, " +
                        "liquides ou solides, exprimées en mm d'équivalent eau. L'application ne distingue donc pas pluie, neige et grêle.",
                    "La plage ${c.windowStart}–${c.windowEnd} est découpée en pas de ${c.stepMinutes} min. Pour chaque pas, les mesures " +
                        "du pluviomètre (une valeur toutes les 5 min) sont additionnées. Le pas compte comme temps de précipitation si ce " +
                        "cumul atteint l'intensité minimale × la durée du pas : ${Fmt.intensity(c)} × ${c.stepMinutes} min = " +
                        "${Fmt.mmPrecise(c.wetStepThresholdMm)} mm ; sinon il compte comme sec.",
                    "Les mesures SPW étant le plus souvent publiées par paliers de 0,1 mm, ce seuil est en général atteint à " +
                        "${Fmt.num(c.effectiveStepThresholdMm, 1)} mm par pas de ${c.stepMinutes} min (le calcul utilise la valeur exacte).",
                    "Durée totale = somme des pas avec précipitations ; durée consécutive = plus longue suite ininterrompue de ces pas.",
                    "Intempérie si précipitations consécutives $op ${Fmt.hoursShort(c.consecutiveRainHours)} " +
                        "OU précipitations totales $op ${Fmt.hoursShort(c.totalRainHours)}.",
                    "Journée orange si précipitations totales ≥ ${Fmt.hoursShort(c.warningRainHours)} sans atteindre l'intempérie.",
                ),
            ),
            Section(
                "4. Température",
                listOf(
                    "Chaque moyenne horaire compte pour la part de l'heure comprise dans la plage (ex. 07:00–08:00 compte pour 30 min).",
                    "Seuil bas (gel) : durée pendant laquelle T < ${Fmt.deg(c.coldThresholdC)} ; atteint si ≥ " +
                        "${Fmt.hoursShort(c.coldMinHours)}. ${if (c.coldCountsAsIntemperie) on else off}.",
                    "Seuil haut (canicule) : durée pendant laquelle T > ${Fmt.deg(c.heatThresholdC)} ; atteint si ≥ " +
                        "${Fmt.hoursShort(c.heatMinHours)}. ${if (c.heatCountsAsIntemperie) on else off}.",
                    "La température moyenne de la plage et les T min./max. horaires sont toujours affichées à titre d'information.",
                ),
            ),
            Section(
                "5. Couleur de la journée",
                listOf(
                    "ROUGE (intempérie) : au moins une des conditions activées est remplie (précipitations, gel ou canicule). " +
                        "Une journée incomplète devient rouge dès qu'un seuil est atteint.",
                    "ORANGE : précipitations ≥ ${Fmt.hoursShort(c.warningRainHours)}, sans intempérie.",
                    "GRIS : moins de ${(c.minCoverage * 100).toInt()} % des mesures de précipitations disponibles dans la plage.",
                    "VERT : aucune des situations précédentes. BLANC : journée en cours ou à venir.",
                    "Le motif de chaque intempérie est indiqué (précipitations consécutives, totales, gel, canicule).",
                ),
            ),
            Section(
                "Détail d'une journée",
                listOf(
                    "Toucher un jour du calendrier affiche deux graphiques superposés sur la plage de travail " +
                        "${c.windowStart}–${c.windowEnd} : les précipitations par pas de ${c.stepMinutes} min (barre pleine = pas compté, " +
                        "barre claire = sous le seuil de ${Fmt.wetThreshold(c)}) et la température horaire avec les seuils gel / canicule.",
                    "L'axe horaire suit automatiquement la plage paramétrée. Toucher ou glisser sur le graphique affiche les valeurs ; " +
                        "un tableau des valeurs est aussi disponible.",
                ),
            ),
            Section(
                "6. Jours ouvrables",
                listOf(
                    "Samedis, dimanches et les 10 jours fériés légaux sont signalés ; les exports comptent à part les " +
                        "intempéries tombant un jour ouvrable. Les congés du bâtiment ne sont pas intégrés.",
                ),
            ),
            Section(
                "7. Exports",
                listOf(
                    "PDF : calendrier A4 coloré, liste des jours d'intempéries avec leurs indications, critères et base réglementaire.",
                    "Excel : même calendrier, tableau journalier chiffré et synthèse par formules (recalculée si un statut est corrigé), " +
                        "feuilles « Mode de fonctionnement » et « Base réglementaire ».",
                    "Chaque export porte la mention « Sources des données : SPW », exigée par les conditions d'utilisation du SPW.",
                ),
            ),
            Section(
                "8. Limites",
                listOf(
                    "Les mesures proviennent de la station la plus proche, pas du chantier lui-même : vérifiez la distance affichée.",
                    "Données indicatives, sans valeur légale. Au sens du CCT Qualiroutes (ch. A, art. 76 § 4, 4°), le jour " +
                        "d'intempérie résulte de l'impossibilité de travailler au moins 4 h, sur reconnaissance de l'adjudicateur : " +
                        "l'application documente cette reconnaissance, elle ne la remplace pas.",
                    "Les conséquences des intempéries (sol détrempé, gelé, enneigé) ne sont pas mesurées : elles relèvent du journal des travaux.",
                ),
            ),
        )
    }
}
