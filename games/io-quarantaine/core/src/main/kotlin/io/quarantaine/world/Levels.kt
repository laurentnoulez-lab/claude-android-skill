package io.quarantaine.world

/** Campagne : 3 niveaux + l'antre du boss. */
object Levels {
    const val MUSIC_TITLE = 0
    const val MUSIC_L1 = 1
    const val MUSIC_L2 = 2
    const val MUSIC_L3 = 3
    const val MUSIC_BOSS = 4
    const val MUSIC_VICTORY = 5

    val all: List<LevelDef> = listOf(
        LevelDef(
            index = 0,
            title = "SECTEUR 1",
            subtitle = "HANGAR DE QUARANTAINE",
            briefing = listOf(
                "Station minière Prométhée, lune Io.",
                "Le signal de détresse s'est tu il y a 6 heures.",
                "Trouvez la carte d'accès bleue et",
                "rejoignez l'interrupteur de SORTIE.",
            ),
            rows = LevelMaps.l1,
            ceil = 2.0f,
            ambient = floatArrayOf(0.20f, 0.21f, 0.24f),
            skyAmbient = floatArrayOf(0.62f, 0.52f, 0.40f),
            fog = floatArrayOf(0.03f, 0.03f, 0.04f),
            fogDensity = 0.055f,
            music = MUSIC_L1,
            startYawDeg = 0f,
            parTime = 150,
        ),
        LevelDef(
            index = 1,
            title = "SECTEUR 2",
            subtitle = "RAFFINERIE DE SOUFRE",
            briefing = listOf(
                "Les coulées de soufre ont envahi la raffinerie.",
                "Ne marchez pas dans le soufre en fusion !",
                "Cartes jaune puis rouge requises",
                "pour atteindre la sortie à l'est.",
            ),
            rows = LevelMaps.l2,
            ceil = 2.25f,
            ambient = floatArrayOf(0.22f, 0.18f, 0.15f),
            skyAmbient = floatArrayOf(0.60f, 0.45f, 0.30f),
            fog = floatArrayOf(0.07f, 0.035f, 0.015f),
            fogDensity = 0.045f,
            music = MUSIC_L2,
            startYawDeg = 0f,
            parTime = 240,
        ),
        LevelDef(
            index = 2,
            title = "SECTEUR 3",
            subtitle = "LABORATOIRES XÉNO",
            briefing = listOf(
                "L'infestation est née ici.",
                "Les trois cartes d'accès sont nécessaires.",
                "Une arme expérimentale serait stockée",
                "dans l'aile de xénobiologie...",
            ),
            rows = LevelMaps.l3,
            ceil = 2.0f,
            ambient = floatArrayOf(0.17f, 0.14f, 0.19f),
            skyAmbient = floatArrayOf(0.5f, 0.4f, 0.4f),
            fog = floatArrayOf(0.035f, 0.01f, 0.035f),
            fogDensity = 0.06f,
            music = MUSIC_L3,
            startYawDeg = 90f,
            parTime = 300,
        ),
        LevelDef(
            index = 3,
            title = "SECTEUR 4",
            subtitle = "CŒUR DU RÉACTEUR",
            briefing = listOf(
                "L'Hôte s'est enraciné dans le réacteur.",
                "Détruisez-le avant qu'il ne gagne la surface.",
                "Servez-vous dans l'armurerie.",
                "Bonne chance, marine.",
            ),
            rows = LevelMaps.l4,
            ceil = 2.0f,
            ambient = floatArrayOf(0.2f, 0.16f, 0.14f),
            skyAmbient = floatArrayOf(0.55f, 0.40f, 0.30f),
            fog = floatArrayOf(0.05f, 0.02f, 0.01f),
            fogDensity = 0.035f,
            music = MUSIC_BOSS,
            startYawDeg = 90f,
            parTime = 180,
            boss = true,
        ),
    )
}
