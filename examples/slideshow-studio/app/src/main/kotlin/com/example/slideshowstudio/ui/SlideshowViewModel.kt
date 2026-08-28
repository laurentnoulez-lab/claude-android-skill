package com.example.slideshowstudio.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.slideshowstudio.data.GalleryPhoto
import com.example.slideshowstudio.data.PhotoRepository
import com.example.slideshowstudio.engine.SlideshowSettings
import com.example.slideshowstudio.engine.StoryboardBuilder
import com.example.slideshowstudio.export.ExportProgress
import com.example.slideshowstudio.export.VideoExporter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlin.random.Random

class SlideshowViewModel(
    private val photoRepository: PhotoRepository,
    private val videoExporter: VideoExporter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SlideshowUiState())
    val uiState: StateFlow<SlideshowUiState> = _uiState.asStateFlow()

    private var exportJob: Job? = null

    fun onAction(action: SlideshowAction) {
        when (action) {
            is SlideshowAction.AddPhotos -> addPhotos(action)
            is SlideshowAction.RemovePhoto -> updatePhotos(_uiState.value.photos.filterNot { it.id == action.id })
            is SlideshowAction.ToggleImportant -> updatePhotos(
                _uiState.value.photos.map { photo ->
                    if (photo.id == action.id) {
                        photo.copy(ref = photo.ref.copy(isImportant = !photo.ref.isImportant))
                    } else {
                        photo
                    }
                },
            )
            SlideshowAction.ClearPhotos -> updatePhotos(emptyList())
            is SlideshowAction.SetSceneDuration -> updateSettings {
                it.copy(sceneDurationSeconds = action.seconds)
            }

            is SlideshowAction.SetTransitionDuration -> updateSettings {
                it.copy(transitionDurationSeconds = action.seconds)
            }

            is SlideshowAction.SetMode -> updateSettings { it.copy(mode = action.mode) }
            is SlideshowAction.SetFormat -> updateSettings { it.copy(format = action.format) }
            is SlideshowAction.SetCropMode -> updateSettings { it.copy(cropMode = action.cropMode) }
            is SlideshowAction.SetPhotoOrder -> updateSettings { it.copy(photoOrder = action.order) }
            is SlideshowAction.SetBackgroundMode -> updateSettings { it.copy(backgroundMode = action.mode) }
            is SlideshowAction.SetBackgroundColor -> updateSettings { it.copy(backgroundColor = action.color) }
            SlideshowAction.Reshuffle -> updateSettings { it.copy(seed = Random.nextLong()) }
            SlideshowAction.StartExport -> startExport()
            SlideshowAction.CancelExport -> cancelExport()
            SlideshowAction.DismissExport -> _uiState.value = _uiState.value.copy(export = ExportUiState.Idle)
        }
    }

    private fun addPhotos(action: SlideshowAction.AddPhotos) {
        if (action.uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true)
            val existing = _uiState.value.photos
            val known = existing.map { it.id }.toSet()
            val imported = photoRepository.loadPhotos(action.uris.filterNot { it.toString() in known })
            _uiState.value = _uiState.value.copy(isImporting = false)
            updatePhotos(existing + imported)
        }
    }

    private fun updatePhotos(photos: List<GalleryPhoto>) {
        _uiState.value = rebuild(_uiState.value.copy(photos = photos))
    }

    private fun updateSettings(transform: (SlideshowSettings) -> SlideshowSettings) {
        val state = _uiState.value
        _uiState.value = rebuild(state.copy(settings = transform(state.settings).sanitized()))
    }

    /** The storyboard is pure and cheap to build, so it is simply rebuilt on every change. */
    private fun rebuild(state: SlideshowUiState): SlideshowUiState = state.copy(
        storyboard = if (state.photos.isEmpty()) {
            null
        } else {
            StoryboardBuilder.build(state.photos.map { it.ref }, state.settings)
        },
    )

    private fun startExport() {
        val state = _uiState.value
        val storyboard = state.storyboard ?: return
        if (storyboard.isEmpty) return
        exportJob?.cancel()
        _uiState.value = state.copy(export = ExportUiState.Running(0f, ExportUiState.Running.Stage.PREPARING))
        exportJob = videoExporter.export(storyboard, state.photos)
            .onEach { progress -> _uiState.value = _uiState.value.copy(export = progress.toUiState()) }
            .catch { error ->
                _uiState.value = _uiState.value.copy(
                    export = ExportUiState.Failed(error.message ?: error::class.java.simpleName),
                )
            }
            .launchIn(viewModelScope)
    }

    private fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _uiState.value = _uiState.value.copy(export = ExportUiState.Idle)
    }

    override fun onCleared() {
        exportJob?.cancel()
        super.onCleared()
    }

    private fun ExportProgress.toUiState(): ExportUiState = when (this) {
        ExportProgress.Preparing -> ExportUiState.Running(0f, ExportUiState.Running.Stage.PREPARING)
        is ExportProgress.Rendering -> ExportUiState.Running(fraction, ExportUiState.Running.Stage.RENDERING)
        ExportProgress.Saving -> ExportUiState.Running(1f, ExportUiState.Running.Stage.SAVING)
        is ExportProgress.Finished -> ExportUiState.Done(video)
    }

    class Factory(
        private val photoRepository: PhotoRepository,
        private val videoExporter: VideoExporter,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SlideshowViewModel(photoRepository, videoExporter) as T
    }
}
