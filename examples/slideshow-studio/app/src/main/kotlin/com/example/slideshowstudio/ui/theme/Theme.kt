package com.example.slideshowstudio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Photos look their best on a dark, neutral surface, so the app commits to a single dark theme. */
private val SlideshowColors = darkColorScheme(
    primary = Color(0xFF9E8CFF),
    onPrimary = Color(0xFF1A1033),
    primaryContainer = Color(0xFF2E2360),
    onPrimaryContainer = Color(0xFFE4DDFF),
    secondary = Color(0xFF4FD9C6),
    onSecondary = Color(0xFF04322C),
    secondaryContainer = Color(0xFF10453E),
    onSecondaryContainer = Color(0xFFB4F2E7),
    background = Color(0xFF0E0E12),
    onBackground = Color(0xFFE8E6EF),
    surface = Color(0xFF14141A),
    onSurface = Color(0xFFE8E6EF),
    surfaceVariant = Color(0xFF23232C),
    onSurfaceVariant = Color(0xFFC2C0CC),
    outline = Color(0xFF4A4A57),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/** Background used behind the photos, in the preview and in the exported video alike. */
val SlideshowCanvasColor = Color(0xFF0E0E12)

@Composable
fun SlideshowStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SlideshowColors,
        content = content,
    )
}
