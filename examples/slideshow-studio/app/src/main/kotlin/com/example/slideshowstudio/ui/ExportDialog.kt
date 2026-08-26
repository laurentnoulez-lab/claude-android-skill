package com.example.slideshowstudio.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.slideshowstudio.R
import com.example.slideshowstudio.export.ExportedVideo
import kotlin.math.roundToInt

/** Progress, then the result: the export is the only long running operation in the app. */
@Composable
fun ExportDialog(
    export: ExportUiState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onShare: (ExportedVideo) -> Unit,
    onOpen: (ExportedVideo) -> Unit,
) {
    when (export) {
        ExportUiState.Idle -> Unit

        is ExportUiState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.export_video)) },
            text = {
                Column {
                    Text(stringResource(R.string.exporting, (export.fraction * 100).roundToInt()))
                    LinearProgressIndicator(
                        progress = { export.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.export_cancel)) }
            },
        )

        is ExportUiState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.export_done)) },
            text = {
                Text(
                    text = "${export.video.width} × ${export.video.height} · " +
                        "${export.video.durationSeconds.roundToInt()} s\n${export.video.file.name}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { onShare(export.video) }) {
                    Text(stringResource(R.string.share_video))
                }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = { onOpen(export.video) }) {
                        Text(stringResource(R.string.open_video))
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
                }
            },
        )

        is ExportUiState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.export_failed, export.message)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            },
        )
    }
}
