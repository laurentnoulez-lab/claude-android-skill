package com.example.slideshowstudio.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.slideshowstudio.AppContainer
import com.example.slideshowstudio.export.ExportedVideo
import com.example.slideshowstudio.export.VideoStore
import com.example.slideshowstudio.ui.editor.EditorScreen
import com.example.slideshowstudio.ui.preview.PreviewScreen

@Composable
fun SlideshowApp(container: AppContainer) {
    val viewModel: SlideshowViewModel = viewModel(
        factory = remember(container) {
            SlideshowViewModel.Factory(container.photoRepository, container.videoExporter)
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showPreview by rememberSaveable { mutableStateOf(false) }
    val storyboard = state.storyboard
    val previewVisible = showPreview && storyboard != null && !storyboard.isEmpty

    BackHandler(enabled = previewVisible) { showPreview = false }

    AnimatedContent(
        targetState = previewVisible,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { preview ->
        if (preview && storyboard != null) {
            PreviewScreen(
                photos = state.photos,
                storyboard = storyboard,
                repository = container.photoRepository,
                onBack = { showPreview = false },
            )
        } else {
            EditorScreen(
                state = state,
                repository = container.photoRepository,
                thumbnails = container.thumbnailCache,
                onAction = viewModel::onAction,
                onOpenPreview = { showPreview = true },
            )
        }
    }

    ExportDialog(
        export = state.export,
        onCancel = { viewModel.onAction(SlideshowAction.CancelExport) },
        onDismiss = { viewModel.onAction(SlideshowAction.DismissExport) },
        onShare = { video -> context.startActivity(shareIntent(context, video)) },
        onOpen = { video -> context.startActivity(openIntent(context, video)) },
    )
}

private fun shareIntent(context: android.content.Context, video: ExportedVideo): Intent {
    val uri = VideoStore.shareableUri(context, video)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, null)
}

private fun openIntent(context: android.content.Context, video: ExportedVideo): Intent =
    Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(VideoStore.shareableUri(context, video), "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
