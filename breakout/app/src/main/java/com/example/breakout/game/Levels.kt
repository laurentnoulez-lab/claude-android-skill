package com.example.breakout.game

/**
 * Dispositions des niveaux. Chaque ligne fait [Levels.COLUMNS] caractères :
 * '.' = pas de brique, '1'..'3' = brique avec ce nombre de points de vie.
 */
object Levels {

    const val COLUMNS = 8

    private val patterns: List<List<String>> = listOf(
        // Niveau 1 : le mur classique.
        listOf(
            "11111111",
            "11111111",
            "11111111",
            "11111111",
        ),
        // Niveau 2 : blindage haut et bas.
        listOf(
            "22222222",
            "11111111",
            "11111111",
            "22222222",
        ),
        // Niveau 3 : damier.
        listOf(
            "2.2.2.2.",
            ".1.1.1.1",
            "2.2.2.2.",
            ".1.1.1.1",
            "2.2.2.2.",
        ),
        // Niveau 4 : pyramide.
        listOf(
            "...22...",
            "..2112..",
            ".211112.",
            "21111112",
        ),
        // Niveau 5 : diamant.
        listOf(
            "...22...",
            "..2112..",
            ".211112.",
            "..2112..",
            "...22...",
        ),
        // Niveau 6 : colonnes.
        listOf(
            "3.2112.3",
            "3.2112.3",
            "3.2112.3",
            "3.2112.3",
            "3.2112.3",
        ),
        // Niveau 7 : la forteresse.
        listOf(
            "33333333",
            "3......3",
            "3.2112.3",
            "3.2112.3",
            "3......3",
            "33333333",
        ),
        // Niveau 8 : le mur final.
        listOf(
            "33333333",
            "32222223",
            "32111123",
            "32111123",
            "32222223",
            "33333333",
        ),
    )

    val count: Int get() = patterns.size

    /** Retourne la disposition du niveau [level] (1-indexé). */
    fun pattern(level: Int): List<String> = patterns[(level - 1).coerceIn(0, patterns.size - 1)]
}
