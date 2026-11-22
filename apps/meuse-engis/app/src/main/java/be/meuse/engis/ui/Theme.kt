package be.meuse.engis.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Couleurs du graphique (palette validée : série bleue, encres neutres, grille discrète). */
@Immutable
data class ChartColors(
    val bar: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val grid: Color,
    val surface: Color,
)

private val LightChart = ChartColors(
    bar = Color(0xFF2A78D6),
    inkPrimary = Color(0xFF0B0B0B),
    inkSecondary = Color(0xFF52514E),
    inkMuted = Color(0xFF898781),
    grid = Color(0xFFE1E0D9),
    surface = Color(0xFFFCFCFB),
)

private val DarkChart = ChartColors(
    bar = Color(0xFF3987E5),
    inkPrimary = Color(0xFFF0EFEC),
    inkSecondary = Color(0xFFC3C2B7),
    inkMuted = Color(0xFF898781),
    grid = Color(0xFF2C2C2A),
    surface = Color(0xFF1A1A19),
)

val LocalChartColors = staticCompositionLocalOf { LightChart }

@Composable
fun MeuseTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val chart = if (dark) DarkChart else LightChart
    val scheme = if (dark) {
        darkColorScheme(
            primary = chart.bar,
            secondaryContainer = Color(0xFF1E3A5F),
            onSecondaryContainer = chart.inkPrimary,
            background = Color(0xFF121211),
            surface = Color(0xFF121211),
            surfaceContainer = chart.surface,
            surfaceContainerHighest = chart.surface,
            onSurface = chart.inkPrimary,
            onSurfaceVariant = chart.inkSecondary,
        )
    } else {
        lightColorScheme(
            primary = chart.bar,
            secondaryContainer = Color(0xFFD6E6FA),
            onSecondaryContainer = chart.inkPrimary,
            background = Color(0xFFF3F3F1),
            surface = Color(0xFFF3F3F1),
            surfaceContainer = chart.surface,
            surfaceContainerHighest = chart.surface,
            onSurface = chart.inkPrimary,
            onSurfaceVariant = chart.inkSecondary,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalChartColors provides chart) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
