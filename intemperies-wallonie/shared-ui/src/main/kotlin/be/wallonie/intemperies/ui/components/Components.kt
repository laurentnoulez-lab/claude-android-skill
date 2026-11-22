package be.wallonie.intemperies.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.wallonie.intemperies.model.DayStatus
import be.wallonie.intemperies.ui.Fmt
import be.wallonie.intemperies.ui.theme.StatusColors

@Composable
fun StatusDot(status: DayStatus, modifier: Modifier = Modifier, size: Int = 12) {
    val color = StatusColors.of(status)
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

@Composable
fun StatusChip(status: DayStatus, modifier: Modifier = Modifier) {
    val bg = StatusColors.of(status).takeIf { it != Color.Transparent && it.alpha > 0f }
        ?: MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(color = bg, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            Fmt.status(status),
            color = if (bg == MaterialTheme.colorScheme.surfaceContainerHigh) MaterialTheme.colorScheme.onSurface else StatusColors.OnStatus,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun CountBadge(count: Int, status: DayStatus, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(status, size = 10)
        Spacer(Modifier.width(4.dp))
        Text("$count $label", style = MaterialTheme.typography.labelMedium)
    }
}

/** Ligne de réglage numérique avec boutons − / + (plus fluide qu'un champ texte sur chantier). */
@Composable
fun StepperRow(
    title: String,
    value: Double,
    step: Double,
    min: Double,
    max: Double,
    format: (Double) -> String,
    onChange: (Double) -> Unit,
    subtitle: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FilledTonalIconButton(onClick = { onChange((value - step).coerceAtLeast(min).round()) }, enabled = value - step >= min - 1e-9) {
            Icon(Icons.Default.Remove, contentDescription = "Diminuer")
        }
        Text(
            format(value),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(76.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        FilledTonalIconButton(onClick = { onChange((value + step).coerceAtMost(max).round()) }, enabled = value + step <= max + 1e-9) {
            Icon(Icons.Default.Add, contentDescription = "Augmenter")
        }
    }
}

private fun Double.round(): Double = Math.round(this * 100.0) / 100.0

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun MonthStrip(statuses: List<DayStatus>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        statuses.forEach { s ->
            val c = StatusColors.of(s)
            Box(
                Modifier
                    .weight(1f)
                    .size(height = 14.dp, width = 1.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (c.alpha == 0f) MaterialTheme.colorScheme.surfaceContainerHigh else c),
            )
        }
    }
}
