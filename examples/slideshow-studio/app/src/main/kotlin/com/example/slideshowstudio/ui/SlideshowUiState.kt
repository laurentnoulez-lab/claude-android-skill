package com.example.slideshowstudio.ui

import android.net.Uri
import com.example.slideshowstudio.audio.AudioTrackInfo
import com.example.slideshowstudio.data.GalleryPhoto
import com.example.slideshowstudio.engine.BackgroundMode
import com.example.slideshowstudio.engine.CropMode
import com.example.slideshowstudio.engine.EndingMode
import com.example.slideshowstudio.engine.ImagesPerSceneMode
import com.example.slideshowstudio.engine.OpeningMode
import com.example.slideshowstudio.engine.OutputFormat
import com.example.slideshowstudio.engine.PhotoOrder
import com.example.slideshowstudio.engine.SlideshowSettings
import com.example.slideshowstudio.engine.Soundtrack
import com.example.slideshowstudio.engine.SoundtrackSettings
import com.example.slideshowstudio.engine.Storyboard
import com.example.slideshowstudio.engine.TrackTransition
import com.example.slideshowstudio.export.ExportedVideo

data class SlideshowUiState(
    val photos: List<GalleryPhoto> = emptyList(),
    val settings: SlideshowSettings = SlideshowSettings(
        sceneDurationSeconds = 4f,
        transitionDurationSeconds = SlideshowSettings.DEFAULT_TRANSITION_SECONDS,
        mode = ImagesPerSceneMode.UP_TO_THREE,
    ),
    val storyboard: Storyboard? = null,
    val music: List<AudioTrackInfo> = emptyList(),
    val soundtrackSettings: SoundtrackSettings = SoundtrackSettings(),
    val soundtrack: Soundtrack? = null,
    /** Photo ids ticked while the user is putting a group together. */
    val selection: Set<String> = emptySet(),
    val isSelecting: Boolean = false,
    val isImporting: Boolean = false,
    val isReadingMusic: Boolean = false,
    val message: EditorMessage? = null,
    val export: ExportUiState = ExportUiState.Idle,
) {
    val hasPhotos: Boolean get() = photos.isNotEmpty()
    val importantCount: Int get() = photos.count { it.ref.isImportant }
    val sceneCount: Int get() = storyboard?.scenes?.size ?: 0
    val totalDurationSeconds: Float get() = storyboard?.totalDurationSeconds ?: 0f
    val canExport: Boolean get() = hasPhotos && export !is ExportUiState.Running && !isImporting

    /** Group identifiers in the order they first appear, so labels stay stable while editing. */
    val groupIds: List<String> get() = photos.mapNotNull { it.ref.groupId }.distinct()

    val groupCount: Int get() = groupIds.size

    /** Letter shown on a photo's badge: A for the first group, B for the second, and so on. */
    fun groupLabel(groupId: String): String {
        val position = groupIds.indexOf(groupId)
        return if (position < 0) "?" else ('A' + (position % 26)).toString()
    }

    val musicDurationSeconds: Float get() = music.sumOf { it.durationSeconds.toDouble() }.toFloat()

    /** True when the chosen music stops before the video does. */
    val musicFallsShort: Boolean get() = music.isNotEmpty() && soundtrack?.coversWholeVideo == false
}

/** Something the app needs to tell the user once, in reaction to what they just did. */
enum class EditorMessage {
    GROUP_NEEDS_TWO,
    GROUP_TOO_LARGE,
    GROUP_HAS_IMPORTANT,
    IMPORTANT_LEFT_GROUP,
    GROUPS_DISSOLVED,
}

sealed interface ExportUiState {
    data object Idle : ExportUiState
    data class Running(val fraction: Float, val stage: Stage) : ExportUiState {
        enum class Stage { PREPARING, RENDERING, MIXING, SAVING }
    }

    data class Done(val video: ExportedVideo) : ExportUiState
    data class Failed(val message: String) : ExportUiState
}

sealed interface SlideshowAction {
    data class AddPhotos(val uris: List<Uri>) : SlideshowAction
    data class RemovePhoto(val id: String) : SlideshowAction
    data class ToggleImportant(val id: String) : SlideshowAction
    data object ClearPhotos : SlideshowAction
    data class SetSceneDuration(val seconds: Float) : SlideshowAction
    data class SetTransitionDuration(val seconds: Float) : SlideshowAction
    data class SetMode(val mode: ImagesPerSceneMode) : SlideshowAction
    data class SetFormat(val format: OutputFormat) : SlideshowAction
    data class SetCropMode(val cropMode: CropMode) : SlideshowAction
    data class SetPhotoOrder(val order: PhotoOrder) : SlideshowAction
    data class SetBackgroundMode(val mode: BackgroundMode) : SlideshowAction
    data class SetBackgroundColor(val color: Int) : SlideshowAction
    data class SetOpening(val opening: OpeningMode) : SlideshowAction
    data class SetEnding(val ending: EndingMode) : SlideshowAction
    data object Reshuffle : SlideshowAction

    // Groups
    data object StartSelection : SlideshowAction
    data object CancelSelection : SlideshowAction
    data class ToggleSelection(val id: String) : SlideshowAction
    data object CreateGroup : SlideshowAction
    data class LeaveGroup(val id: String) : SlideshowAction
    data object DismissMessage : SlideshowAction

    // Soundtrack
    data class AddMusic(val uris: List<Uri>) : SlideshowAction
    data class RemoveMusic(val id: String) : SlideshowAction
    data class MoveMusic(val id: String, val offset: Int) : SlideshowAction
    data class SetTrackTransition(val transition: TrackTransition) : SlideshowAction
    data class SetCrossfadeSeconds(val seconds: Float) : SlideshowAction
    data class SetMusicFadeIn(val enabled: Boolean) : SlideshowAction
    data class SetMusicFadeOut(val enabled: Boolean) : SlideshowAction
    data class SetMusicFadeSeconds(val seconds: Float) : SlideshowAction
    data class SetMusicVolume(val volume: Float) : SlideshowAction

    data object StartExport : SlideshowAction
    data object CancelExport : SlideshowAction
    data object DismissExport : SlideshowAction
}
