package be.wallonie.intemperies.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import be.wallonie.intemperies.model.DayStatus

object StatusColors {
    val Dry = Color(0xFF81C784)
    val Warning = Color(0xFFFFB74D)
    val Intemperie = Color(0xFFE57373)
    val NoData = Color(0xFFBDBDBD)
    val Pending = Color(0x00000000)
    val Frost = Color(0xFF1565C0)
    val Heat = Color(0xFFD84315)
    val OnStatus = Color(0xFF1B1B1B)

    fun of(status: DayStatus): Color = when (status) {
        DayStatus.DRY -> Dry
        DayStatus.WARNING -> Warning
        DayStatus.INTEMPERIE -> Intemperie
        DayStatus.NO_DATA -> NoData
        DayStatus.IN_PROGRESS, DayStatus.FUTURE -> Pending
    }
}

private val Light = lightColorScheme(
    primary = Color(0xFF1F4E79),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F5),
    onPrimaryContainer = Color(0xFF0B2740),
    secondary = Color(0xFF4F6070),
    secondaryContainer = Color(0xFFDCE6F0),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFECF0F5),
    surfaceContainerHigh = Color(0xFFE5EAF0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA2C9EE),
    onPrimary = Color(0xFF0B2740),
    primaryContainer = Color(0xFF1F4E79),
    onPrimaryContainer = Color(0xFFD3E4F5),
    secondary = Color(0xFFB7C8D9),
    secondaryContainer = Color(0xFF38495A),
)

@Composable
fun IntemperiesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
