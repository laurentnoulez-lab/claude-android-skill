package com.example.slideshowstudio.ui

import android.net.Uri
import com.example.slideshowstudio.data.GalleryPhoto
import com.example.slideshowstudio.engine.ImagesPerSceneMode
import com.example.slideshowstudio.export.ExportedVideo
import com.example.slideshowstudio.engine.SlideshowSettings
import com.example.slideshowstudio.engine.Storyboard

data class SlideshowUiState(
    val photos: List<GalleryPhoto> = emptyList(),
    val settings: SlideshowSettings = SlideshowSettings(
        sceneDurationSeconds = 4f,
        transitionDurationSeconds = SlideshowSettings.DEFAULT_TRANSITION_SECONDS,
        mode = ImagesPerSceneMode.UP_TO_THREE,
    ),
    val storyboard: Storyboard? = null,
    val isImporting: Boolean = false,
    val export: ExportUiState = ExportUiState.Idle,
) {
    val hasPhotos: Boolean get() = photos.isNotEmpty()
    val sceneCount: Int get() = storyboard?.scenes?.size ?: 0
    val totalDurationSeconds: Float get() = storyboard?.totalDurationSeconds ?: 0f
    val canExport: Boolean get() = hasPhotos && export !is ExportUiState.Running && !isImporting
}

sealed interface ExportUiState {
    data object Idle : ExportUiState
    data class Running(val fraction: Float, val stage: Stage) : ExportUiState {
        enum class Stage { PREPARING, RENDERING, SAVING }
    }

    data class Done(val video: ExportedVideo) : ExportUiState
    data class Failed(val message: String) : ExportUiState
}

sealed interface SlideshowAction {
    data class AddPhotos(val uris: List<Uri>) : SlideshowAction
    data class RemovePhoto(val id: String) : SlideshowAction
    data object ClearPhotos : SlideshowAction
    data class SetSceneDuration(val seconds: Float) : SlideshowAction
    data class SetTransitionDuration(val seconds: Float) : SlideshowAction
    data class SetMode(val mode: ImagesPerSceneMode) : SlideshowAction
    data object Reshuffle : SlideshowAction
    data object StartExport : SlideshowAction
    data object CancelExport : SlideshowAction
    data object DismissExport : SlideshowAction
}
