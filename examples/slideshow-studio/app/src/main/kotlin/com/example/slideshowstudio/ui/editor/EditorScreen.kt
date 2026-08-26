package com.example.slideshowstudio.ui.editor

import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.slideshowstudio.R
import com.example.slideshowstudio.data.PhotoRepository
import com.example.slideshowstudio.engine.ImagesPerSceneMode
import com.example.slideshowstudio.engine.SlideshowSettings
import com.example.slideshowstudio.ui.SlideshowAction
import com.example.slideshowstudio.ui.SlideshowUiState
import com.example.slideshowstudio.ui.components.PhotoThumbnail
import com.example.slideshowstudio.ui.components.ThumbnailCache
import kotlin.math.roundToInt

private const val MAX_PHOTOS = 60

/** The picker rejects a limit above what the platform supports, so ask it first. */
private fun maxSelectablePhotos(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    MediaStore.getPickImagesMaxLimit().coerceIn(2, MAX_PHOTOS)
} else {
    MAX_PHOTOS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: SlideshowUiState,
    repository: PhotoRepository,
    thumbnails: ThumbnailCache,
    onAction: (SlideshowAction) -> Unit,
    onOpenPreview: () -> Unit,
) {
    val maxItems = remember { maxSelectablePhotos() }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems),
    ) { uris ->
        onAction(SlideshowAction.AddPhotos(uris))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.editor_title))
                        Text(
                            text = stringResource(R.string.editor_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onOpenPreview,
                        enabled = state.hasPhotos,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text(
                            text = stringResource(R.string.open_preview),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Button(
                        onClick = { onAction(SlideshowAction.StartExport) },
                        enabled = state.canExport,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Movie, contentDescription = null)
                        Text(
                            text = stringResource(R.string.export_video),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            SettingsCard(
                state = state,
                onAction = onAction,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ImportRow(
                state = state,
                onImport = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onAction = onAction,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (state.hasPhotos) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(state.photos, key = { it.id }) { photo ->
                        Box {
                            PhotoThumbnail(
                                photo = photo,
                                repository = repository,
                                cache = thumbnails,
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                            IconButton(
                                onClick = { onAction(SlideshowAction.RemovePhoto(photo.id)) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(28.dp)
                                    .clip(CircleShape),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.remove_photo),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            } else {
                EmptyState(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ImportRow(
    state: SlideshowUiState,
    onImport: () -> Unit,
    onAction: (SlideshowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(onClick = onImport) {
            Text(
                text = stringResource(
                    if (state.hasPhotos) R.string.add_photos else R.string.import_photos,
                ),
            )
        }
        if (state.hasPhotos) {
            OutlinedButton(onClick = { onAction(SlideshowAction.Reshuffle) }) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
            }
            OutlinedButton(onClick = { onAction(SlideshowAction.ClearPhotos) }) {
                Text(stringResource(R.string.clear_photos))
            }
        }
        if (state.isImporting) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SettingsCard(
    state: SlideshowUiState,
    onAction: (SlideshowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.scene_duration, format(state.settings.sceneDurationSeconds)),
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = state.settings.sceneDurationSeconds,
                onValueChange = { onAction(SlideshowAction.SetSceneDuration(round(it))) },
                valueRange = SlideshowSettings.MIN_SCENE_SECONDS..SlideshowSettings.MAX_SCENE_SECONDS,
                steps = 9,
            )

            Text(
                text = stringResource(
                    R.string.transition_duration,
                    format(state.settings.effectiveTransitionSeconds),
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = state.settings.transitionDurationSeconds,
                onValueChange = { onAction(SlideshowAction.SetTransitionDuration(round(it))) },
                valueRange = SlideshowSettings.MIN_TRANSITION_SECONDS..SlideshowSettings.MAX_TRANSITION_SECONDS,
                steps = 4,
            )

            Text(
                text = stringResource(R.string.images_per_scene),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            ModeChips(
                selected = state.settings.mode,
                onSelect = { onAction(SlideshowAction.SetMode(it)) },
            )

            if (state.hasPhotos) {
                Text(
                    text = stringResource(R.string.summary_scenes, state.sceneCount) + " · " +
                        stringResource(R.string.summary_duration, formatDuration(state.totalDurationSeconds)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Text(
                text = stringResource(R.string.summary_format),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeChips(
    selected: ImagesPerSceneMode,
    onSelect: (ImagesPerSceneMode) -> Unit,
) {
    val labels = listOf(
        ImagesPerSceneMode.SINGLE to R.string.mode_single,
        ImagesPerSceneMode.UP_TO_TWO to R.string.mode_up_to_two,
        ImagesPerSceneMode.UP_TO_THREE to R.string.mode_up_to_three,
        ImagesPerSceneMode.UP_TO_FOUR to R.string.mode_up_to_four,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (mode, label) ->
                    FilterChip(
                        selected = mode == selected,
                        onClick = { onSelect(mode) },
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = stringResource(R.string.empty_state_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.empty_state_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Sliders move in half seconds: fine enough to matter, coarse enough to stay easy to hit. */
private fun round(value: Float): Float = (value * 2f).roundToInt() / 2f

private fun format(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else String.format("%.1f", value)

private fun formatDuration(seconds: Float): String {
    val total = seconds.roundToInt()
    return if (total >= 60) "${total / 60} min ${total % 60} s" else "$total s"
}
