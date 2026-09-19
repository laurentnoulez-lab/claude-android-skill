package com.example.slideshowstudio.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.slideshowstudio.audio.AudioLibrary
import com.example.slideshowstudio.data.GalleryPhoto
import com.example.slideshowstudio.data.PhotoRepository
import com.example.slideshowstudio.engine.SlideshowSettings
import com.example.slideshowstudio.engine.SoundtrackPlanner
import com.example.slideshowstudio.engine.SoundtrackSettings
import com.example.slideshowstudio.engine.StoryboardBuilder
import com.example.slideshowstudio.export.ExportProgress
import com.example.slideshowstudio.export.VideoExporter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.random.Random

class SlideshowViewModel(
    private val photoRepository: PhotoRepository,
    private val audioLibrary: AudioLibrary,
    private val videoExporter: VideoExporter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SlideshowUiState())
    val uiState: StateFlow<SlideshowUiState> = _uiState.asStateFlow()

    private var exportJob: Job? = null

    fun onAction(action: SlideshowAction) {
        when (action) {
            is SlideshowAction.AddPhotos -> addPhotos(action)
            is SlideshowAction.RemovePhoto -> updatePhotos(_uiState.value.photos.filterNot { it.id == action.id })
            is SlideshowAction.ToggleImportant -> toggleImportant(action.id)
            SlideshowAction.ClearPhotos -> updatePhotos(emptyList())

            is SlideshowAction.SetSceneDuration -> updateSettings { it.copy(sceneDurationSeconds = action.seconds) }
            is SlideshowAction.SetTransitionDuration -> updateSettings {
                it.copy(transitionDurationSeconds = action.seconds)
            }

            is SlideshowAction.SetMode -> setMode(action)
            is SlideshowAction.SetFormat -> updateSettings { it.copy(format = action.format) }
            is SlideshowAction.SetCropMode -> updateSettings { it.copy(cropMode = action.cropMode) }
            is SlideshowAction.SetPhotoOrder -> updateSettings { it.copy(photoOrder = action.order) }
            is SlideshowAction.SetBackgroundMode -> updateSettings { it.copy(backgroundMode = action.mode) }
            is SlideshowAction.SetBackgroundColor -> updateSettings { it.copy(backgroundColor = action.color) }
            is SlideshowAction.SetOpening -> updateSettings { it.copy(opening = action.opening) }
            is SlideshowAction.SetEnding -> updateSettings { it.copy(ending = action.ending) }
            SlideshowAction.Reshuffle -> updateSettings { it.copy(seed = Random.nextLong()) }

            SlideshowAction.StartSelection -> update {
                it.copy(isSelecting = true, selection = emptySet())
            }

            SlideshowAction.CancelSelection -> update { it.copy(isSelecting = false, selection = emptySet()) }
            is SlideshowAction.ToggleSelection -> update { state ->
                val selection = if (action.id in state.selection) {
                    state.selection - action.id
                } else {
                    state.selection + action.id
                }
                state.copy(selection = selection)
            }

            SlideshowAction.CreateGroup -> createGroup()
            is SlideshowAction.LeaveGroup -> leaveGroup(action.id)
            SlideshowAction.DismissMessage -> update { it.copy(message = null) }

            is SlideshowAction.AddMusic -> addMusic(action)
            is SlideshowAction.RemoveMusic -> updateMusic(_uiState.value.music.filterNot { it.id == action.id })
            is SlideshowAction.MoveMusic -> moveMusic(action)
            is SlideshowAction.SetTrackTransition -> updateSoundtrack { it.copy(transition = action.transition) }
            is SlideshowAction.SetCrossfadeSeconds -> updateSoundtrack { it.copy(crossfadeSeconds = action.seconds) }
            is SlideshowAction.SetMusicFadeIn -> updateSoundtrack { it.copy(fadeIn = action.enabled) }
            is SlideshowAction.SetMusicFadeOut -> updateSoundtrack { it.copy(fadeOut = action.enabled) }
            is SlideshowAction.SetMusicFadeSeconds -> updateSoundtrack { it.copy(fadeSeconds = action.seconds) }
            is SlideshowAction.SetMusicVolume -> updateSoundtrack { it.copy(volume = action.volume) }

            SlideshowAction.StartExport -> startExport()
            SlideshowAction.CancelExport -> cancelExport()
            SlideshowAction.DismissExport -> update { it.copy(export = ExportUiState.Idle) }
        }
    }

    private fun addPhotos(action: SlideshowAction.AddPhotos) {
        if (action.uris.isEmpty()) return
        viewModelScope.launch {
            update { it.copy(isImporting = true) }
            val existing = _uiState.value.photos
            val known = existing.map { it.id }.toSet()
            val imported = photoRepository.loadPhotos(action.uris.filterNot { it.toString() in known })
            update { it.copy(isImporting = false) }
            updatePhotos(existing + imported)
        }
    }

    /** Marking a photo as important takes it out of any group: an important photo is shown alone. */
    private fun toggleImportant(id: String) {
        val state = _uiState.value
        val target = state.photos.firstOrNull { it.id == id } ?: return
        val becomingImportant = !target.ref.isImportant
        val leavesGroup = becomingImportant && target.ref.groupId != null
        val photos = state.photos.map { photo ->
            if (photo.id != id) {
                photo
            } else {
                photo.copy(
                    ref = photo.ref.copy(
                        isImportant = becomingImportant,
                        groupId = if (becomingImportant) null else photo.ref.groupId,
                    ),
                )
            }
        }
        updatePhotos(tidyGroups(photos))
        if (leavesGroup) update { it.copy(message = EditorMessage.IMPORTANT_LEFT_GROUP) }
    }

    /**
     * Groups may never hold more photos than a scene can show, so lowering that limit dissolves the
     * groups that no longer fit rather than quietly breaking either rule.
     */
    private fun setMode(action: SlideshowAction.SetMode) {
        val state = _uiState.value
        val tooLarge = state.photos
            .mapNotNull { it.ref.groupId }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > action.mode.maxImages }
            .keys
        if (tooLarge.isNotEmpty()) {
            val photos = state.photos.map { photo ->
                if (photo.ref.groupId in tooLarge) photo.copy(ref = photo.ref.copy(groupId = null)) else photo
            }
            _uiState.value = rebuild(
                state.copy(
                    photos = photos,
                    settings = state.settings.copy(mode = action.mode).sanitized(),
                    message = EditorMessage.GROUPS_DISSOLVED,
                ),
            )
        } else {
            updateSettings { it.copy(mode = action.mode) }
        }
    }

    private fun createGroup() {
        val state = _uiState.value
        val selected = state.photos.filter { it.id in state.selection }
        val maximum = state.settings.mode.maxImages
        val problem = when {
            selected.size < 2 -> EditorMessage.GROUP_NEEDS_TWO
            selected.size > maximum -> EditorMessage.GROUP_TOO_LARGE
            selected.any { it.ref.isImportant } -> EditorMessage.GROUP_HAS_IMPORTANT
            else -> null
        }
        if (problem != null) {
            update { it.copy(message = problem) }
            return
        }

        val groupId = "group-${System.currentTimeMillis()}"
        val photos = state.photos.map { photo ->
            if (photo.id in state.selection) photo.copy(ref = photo.ref.copy(groupId = groupId)) else photo
        }
        _uiState.value = rebuild(
            state.copy(photos = tidyGroups(photos), isSelecting = false, selection = emptySet()),
        )
    }

    private fun leaveGroup(id: String) {
        val photos = _uiState.value.photos.map { photo ->
            if (photo.id == id) photo.copy(ref = photo.ref.copy(groupId = null)) else photo
        }
        updatePhotos(tidyGroups(photos))
    }

    /** A group with a single photo left is no longer a group. */
    private fun tidyGroups(photos: List<GalleryPhoto>): List<GalleryPhoto> {
        val counts = photos.mapNotNull { it.ref.groupId }.groupingBy { it }.eachCount()
        return photos.map { photo ->
            val group = photo.ref.groupId
            if (group != null && (counts[group] ?: 0) < 2) {
                photo.copy(ref = photo.ref.copy(groupId = null))
            } else {
                photo
            }
        }
    }

    private fun addMusic(action: SlideshowAction.AddMusic) {
        if (action.uris.isEmpty()) return
        viewModelScope.launch {
            update { it.copy(isReadingMusic = true) }
            val known = _uiState.value.music.map { it.id }.toSet()
            val added = audioLibrary.read(action.uris.filterNot { it.toString() in known })
            update { it.copy(isReadingMusic = false) }
            updateMusic(_uiState.value.music + added)
        }
    }

    private fun moveMusic(action: SlideshowAction.MoveMusic) {
        val music = _uiState.value.music.toMutableList()
        val from = music.indexOfFirst { it.id == action.id }
        if (from < 0) return
        val to = (from + action.offset).coerceIn(0, music.lastIndex)
        if (to == from) return
        music.add(to, music.removeAt(from))
        updateMusic(music)
    }

    private fun updatePhotos(photos: List<GalleryPhoto>) {
        _uiState.value = rebuild(_uiState.value.copy(photos = photos))
    }

    private fun updateMusic(music: List<com.example.slideshowstudio.audio.AudioTrackInfo>) {
        _uiState.value = rebuild(_uiState.value.copy(music = music))
    }

    private fun updateSettings(transform: (SlideshowSettings) -> SlideshowSettings) {
        val state = _uiState.value
        _uiState.value = rebuild(state.copy(settings = transform(state.settings).sanitized()))
    }

    private fun updateSoundtrack(transform: (SoundtrackSettings) -> SoundtrackSettings) {
        val state = _uiState.value
        _uiState.value = rebuild(state.copy(soundtrackSettings = transform(state.soundtrackSettings).sanitized()))
    }

    private fun update(transform: (SlideshowUiState) -> SlideshowUiState) {
        _uiState.value = transform(_uiState.value)
    }

    /**
     * The storyboard and the soundtrack are pure and cheap to build, so they are simply rebuilt on
     * every change rather than patched.
     */
    private fun rebuild(state: SlideshowUiState): SlideshowUiState {
        val storyboard = if (state.photos.isEmpty()) {
            null
        } else {
            StoryboardBuilder.build(state.photos.map { it.ref }, state.settings)
        }
        val soundtrack = if (storyboard == null || state.music.isEmpty()) {
            null
        } else {
            SoundtrackPlanner.plan(
                tracks = state.music.map { it.toRef() },
                videoDurationSeconds = storyboard.totalDurationSeconds,
                rawSettings = state.soundtrackSettings,
            )
        }
        return state.copy(storyboard = storyboard, soundtrack = soundtrack)
    }

    private fun startExport() {
        val state = _uiState.value
        val storyboard = state.storyboard ?: return
        if (storyboard.isEmpty) return
        exportJob?.cancel()
        _uiState.value = state.copy(export = ExportUiState.Running(0f, ExportUiState.Running.Stage.PREPARING))
        exportJob = videoExporter
            .export(
                storyboard = storyboard,
                photos = state.photos,
                music = state.music,
                soundtrackSettings = state.soundtrackSettings,
            )
            .onEach { progress -> update { it.copy(export = progress.toUiState()) } }
            .catch { error ->
                update {
                    it.copy(export = ExportUiState.Failed(error.message ?: error::class.java.simpleName))
                }
            }
            .launchIn(viewModelScope)
    }

    private fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        update { it.copy(export = ExportUiState.Idle) }
    }

    override fun onCleared() {
        exportJob?.cancel()
        super.onCleared()
    }

    private fun ExportProgress.toUiState(): ExportUiState = when (this) {
        ExportProgress.Preparing -> ExportUiState.Running(0f, ExportUiState.Running.Stage.PREPARING)
        is ExportProgress.Rendering -> ExportUiState.Running(fraction, ExportUiState.Running.Stage.RENDERING)
        is ExportProgress.MixingAudio -> ExportUiState.Running(fraction, ExportUiState.Running.Stage.MIXING)
        ExportProgress.Saving -> ExportUiState.Running(1f, ExportUiState.Running.Stage.SAVING)
        is ExportProgress.Finished -> ExportUiState.Done(video)
    }

    class Factory(
        private val photoRepository: PhotoRepository,
        private val audioLibrary: AudioLibrary,
        private val videoExporter: VideoExporter,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SlideshowViewModel(photoRepository, audioLibrary, videoExporter) as T
    }
}
