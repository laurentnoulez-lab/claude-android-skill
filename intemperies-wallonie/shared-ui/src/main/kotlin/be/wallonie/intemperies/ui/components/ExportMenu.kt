package be.wallonie.intemperies.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import be.wallonie.intemperies.export.ExportFormat

/** Menu « Exporter » : PDF (calendrier à imprimer) ou Excel (calendrier + tableau chiffré). */
@Composable
fun ExportMenu(expanded: Boolean, onDismiss: () -> Unit, onSelect: (ExportFormat) -> Unit, subtitle: String? = null) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        ExportFormat.entries.forEach { f ->
            DropdownMenuItem(
                text = { Text(if (subtitle != null) "${f.label} — $subtitle" else f.label) },
                leadingIcon = {
                    Icon(if (f == ExportFormat.PDF) Icons.Default.PictureAsPdf else Icons.Default.GridOn, null)
                },
                onClick = {
                    onDismiss()
                    onSelect(f)
                },
            )
        }
    }
}
