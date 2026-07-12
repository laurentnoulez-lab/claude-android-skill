package com.example.breakout.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.breakout.game.PowerUpType

object GameColors {
    val backgroundTop = Color(0xFF0B1020)
    val backgroundBottom = Color(0xFF16204A)
    val accent = Color(0xFF4DD0E1)
    val paddleStart = Color(0xFF26C6DA)
    val paddleEnd = Color(0xFF0097A7)
    val ball = Color(0xFFFFFFFF)
    val textPrimary = Color(0xFFECEFF7)
    val textSecondary = Color(0xFF8B93B0)
    val heart = Color(0xFFFF5C77)
    val scrim = Color(0xB3060A18)

    val backgroundBrush = Brush.verticalGradient(
        listOf(backgroundTop, backgroundBottom),
    )

    /** Couleur d'une brique selon ses points de vie restants. */
    fun brickColor(hp: Int): Color = when (hp) {
        1 -> Color(0xFF4FC3F7)
        2 -> Color(0xFFFFB74D)
        else -> Color(0xFFFF5252)
    }

    fun powerUpColor(type: PowerUpType): Color = when (type) {
        PowerUpType.EXPAND -> Color(0xFF66BB6A)
        PowerUpType.EXTRA_LIFE -> Color(0xFFFF8A80)
        PowerUpType.MULTI_BALL -> Color(0xFFBA68C8)
        PowerUpType.SLOW_BALL -> Color(0xFF4DB6AC)
    }

    fun powerUpLabel(type: PowerUpType): String = when (type) {
        PowerUpType.EXPAND -> "↔"
        PowerUpType.EXTRA_LIFE -> "+"
        PowerUpType.MULTI_BALL -> "3"
        PowerUpType.SLOW_BALL -> "S"
    }
}

private val BreakoutColorScheme = darkColorScheme(
    primary = GameColors.accent,
    onPrimary = Color(0xFF06131A),
    background = GameColors.backgroundTop,
    onBackground = GameColors.textPrimary,
    surface = Color(0xFF141B36),
    onSurface = GameColors.textPrimary,
)

@Composable
fun BreakoutTheme(content: @Composable () -> Unit) {
    // Le jeu assume un thème sombre, quel que soit le réglage système.
    MaterialTheme(
        colorScheme = BreakoutColorScheme,
        content = content,
    )
}
