package com.diaporama.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.diaporama.app.render.SlideshowBuilder
import com.diaporama.app.render.SlideshowConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

data class UiState(
    val photos: List<Uri> = emptyList(),
    val photosPerScreen: Int = 3,
    val secondsPerScreen: Float = 3.5f,
    val transitionSeconds: Float = 1.0f,
    val isRendering: Boolean = false,
    val progress: Float = 0f,
    val resultUri: Uri? = null,
    val error: String? = null,
) {
    /** Estimated final video length in seconds. */
    val estimatedDuration: Float
        get() {
            if (photos.isEmpty()) return 0f
            val screens = ceil(photos.size.toFloat() / photosPerScreen).toInt()
            return screens * secondsPerScreen + (screens - 1).coerceAtLeast(0) * transitionSeconds
        }
}

class SlideshowViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var builder: SlideshowBuilder? = null

    fun setPhotos(uris: List<Uri>) {
        _state.value = _state.value.copy(photos = uris, resultUri = null, error = null)
    }

    fun setPhotosPerScreen(value: Int) {
        _state.value = _state.value.copy(photosPerScreen = value.coerceIn(1, 6))
    }

    fun setSecondsPerScreen(value: Float) {
        _state.value = _state.value.copy(secondsPerScreen = value)
    }

    fun setTransitionSeconds(value: Float) {
        _state.value = _state.value.copy(transitionSeconds = value)
    }

    fun reset() {
        _state.value = _state.value.copy(resultUri = null, error = null, progress = 0f)
    }

    fun cancel() {
        builder?.cancel()
    }

    fun generate() {
        val current = _state.value
        if (current.isRendering || current.photos.isEmpty()) return

        _state.value = current.copy(isRendering = true, progress = 0f, error = null, resultUri = null)

        viewModelScope.launch {
            val context = getApplication<Application>()
            val config = SlideshowConfig(
                photosPerScreen = current.photosPerScreen,
                secondsPerScreen = current.secondsPerScreen,
                transitionSeconds = current.transitionSeconds,
            )
            val outDir = File(context.cacheDir, "videos").apply { mkdirs() }
            val outFile = File(outDir, "diaporama_${System.currentTimeMillis()}.mp4")

            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val b = SlideshowBuilder(context, config)
                    builder = b
                    val ok = b.build(current.photos, outFile) { p ->
                        _state.value = _state.value.copy(progress = p)
                    }
                    if (!ok) return@runCatching null
                    val name = "Diaporama_${System.currentTimeMillis()}.mp4"
                    val uri = MediaStoreSaver.save(context, outFile, name)
                    outFile.delete()
                    uri
                }
            }

            builder = null
            result.fold(
                onSuccess = { uri ->
                    _state.value = if (uri != null) {
                        _state.value.copy(isRendering = false, progress = 1f, resultUri = uri)
                    } else {
                        _state.value.copy(isRendering = false, progress = 0f)
                    }
                },
                onFailure = { e ->
                    outFile.delete()
                    _state.value = _state.value.copy(
                        isRendering = false,
                        error = e.message ?: "Erreur lors de la création",
                    )
                },
            )
        }
    }

    fun percentText(): String = (_state.value.progress * 100).roundToInt().toString() + " %"
}
