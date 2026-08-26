package com.example.slideshowstudio.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.slideshowstudio.R
import com.example.slideshowstudio.data.GalleryPhoto
import com.example.slideshowstudio.data.PhotoRepository
import com.example.slideshowstudio.engine.FrameComposer
import com.example.slideshowstudio.engine.PhotoRef
import com.example.slideshowstudio.engine.SourceResolution
import com.example.slideshowstudio.engine.Storyboard
import com.example.slideshowstudio.render.drawSlideshowFrame
import com.example.slideshowstudio.ui.theme.SlideshowCanvasColor
import kotlinx.coroutines.isActive
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The preview renders at this size instead of 1080p: the animations and transitions are identical
 * because both renderers consume the same engine frames, but playback stays smooth on any device.
 */
private const val PREVIEW_WIDTH = 960
private const val PREVIEW_HEIGHT = 540
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MAX_STEP_SECONDS = 0.1f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    photos: List<GalleryPhoto>,
    storyboard: Storyboard,
    repository: PhotoRepository,
    onBack: () -> Unit,
) {
    val refs = remember(photos) { photos.map { it.ref } }
    val composer = remember(storyboard, refs) { FrameComposer(storyboard, refs) }
    val player = remember(storyboard) { PreviewPlayerState(composer.totalDurationSeconds) }
    val scope = rememberCoroutineScope()
    val store = remember(storyboard, photos) {
        PreviewImageStore(
            scope = scope,
            repository = repository,
            photos = photos,
            decodeWidths = previewDecodeWidths(storyboard, refs),
        )
    }

    // Reading the position through derivedStateOf keeps the screen from recomposing on every frame:
    // only a scene change matters here, the canvas reads the position in the draw phase.
    val sceneIndex by remember(storyboard, player) {
        derivedStateOf { sceneIndexAt(player.positionSeconds, storyboard) }
    }
    val neededPhotos = remember(storyboard, sceneIndex) { photoIndicesAround(storyboard, sceneIndex) }

    LaunchedEffect(neededPhotos) { store.ensureLoaded(neededPhotos) }

    // Playback follows the display clock, so it plays at the real speed on any refresh rate.
    LaunchedEffect(player.isPlaying, storyboard) {
        if (!player.isPlaying) return@LaunchedEffect
        var previousFrameTime = withFrameNanos { it }
        while (isActive && player.isPlaying) {
            withFrameNanos { now ->
                val delta = (now - previousFrameTime) / NANOS_PER_SECOND
                previousFrameTime = now
                player.advance(delta.coerceIn(0f, MAX_STEP_SECONDS))
            }
        }
    }

    DisposableEffect(storyboard) {
        player.play()
        onDispose { player.pause() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preview_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(storyboard.settings.canvasAspect)
                    .background(SlideshowCanvasColor),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawSlideshowFrame(
                        frame = composer.compose(player.positionSeconds),
                        background = SlideshowCanvasColor,
                        image = { index -> store[index] },
                    )
                }
                if (!store.isReady(currentScenePhotos(storyboard, sceneIndex))) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
            }

            PlaybackControls(
                player = player,
                durationSeconds = composer.totalDurationSeconds,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    player: PreviewPlayerState,
    durationSeconds: Float,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Slider(
            value = player.positionSeconds,
            onValueChange = { player.seekTo(it) },
            valueRange = 0f..durationSeconds.coerceAtLeast(0.001f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${formatTime(player.positionSeconds)} / ${formatTime(durationSeconds)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = { player.restart() }) {
                    Icon(
                        imageVector = Icons.Filled.Replay,
                        contentDescription = stringResource(R.string.restart),
                    )
                }
                FilledIconButton(onClick = { player.toggle() }) {
                    Icon(
                        imageVector = if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (player.isPlaying) R.string.pause else R.string.play,
                        ),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.preview_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun sceneIndexAt(positionSeconds: Float, storyboard: Storyboard): Int {
    if (storyboard.isEmpty) return 0
    val index = floor(positionSeconds / storyboard.sceneDurationSeconds).toInt()
    return index.coerceIn(0, storyboard.scenes.size - 1)
}

private fun currentScenePhotos(storyboard: Storyboard, sceneIndex: Int): Set<Int> =
    storyboard.scenes.getOrNull(sceneIndex)?.slots?.map { it.photoIndex }?.toSet() ?: emptySet()

/** Photos of the current scene plus its neighbours, so a transition never waits on decoding. */
private fun photoIndicesAround(storyboard: Storyboard, sceneIndex: Int): Set<Int> {
    val indices = mutableSetOf<Int>()
    for (offset in -1..1) {
        val scene = storyboard.scenes.getOrNull(sceneIndex + offset) ?: continue
        scene.slots.forEach { indices += it.photoIndex }
    }
    return indices
}

private fun previewDecodeWidths(storyboard: Storyboard, refs: List<PhotoRef>): Map<Int, Int> {
    val previewStoryboard = storyboard.copy(
        settings = storyboard.settings.copy(outputWidth = PREVIEW_WIDTH, outputHeight = PREVIEW_HEIGHT),
    )
    return SourceResolution.forStoryboard(previewStoryboard, refs, maxWidth = PREVIEW_WIDTH)
}

private fun formatTime(seconds: Float): String {
    val total = seconds.roundToInt()
    return "%d:%02d".format(total / 60, total % 60)
}
