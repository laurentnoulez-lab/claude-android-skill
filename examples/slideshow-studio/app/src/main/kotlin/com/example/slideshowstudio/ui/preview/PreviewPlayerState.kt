package com.example.slideshowstudio.ui.preview

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Play / pause / rewind state of the preview. */
@Stable
class PreviewPlayerState(val durationSeconds: Float) {

    var isPlaying by mutableStateOf(false)
        private set

    var positionSeconds by mutableFloatStateOf(0f)
        private set

    val isFinished: Boolean get() = positionSeconds >= durationSeconds - END_EPSILON

    fun play() {
        if (isFinished) positionSeconds = 0f
        isPlaying = true
    }

    fun pause() {
        isPlaying = false
    }

    fun toggle() {
        if (isPlaying) pause() else play()
    }

    fun restart() {
        positionSeconds = 0f
        isPlaying = true
    }

    fun seekTo(seconds: Float) {
        positionSeconds = seconds.coerceIn(0f, durationSeconds)
    }

    /** Advances playback; stops on the last frame instead of looping. */
    fun advance(deltaSeconds: Float) {
        if (!isPlaying) return
        val next = positionSeconds + deltaSeconds
        if (next >= durationSeconds) {
            positionSeconds = durationSeconds
            isPlaying = false
        } else {
            positionSeconds = next
        }
    }

    private companion object {
        const val END_EPSILON = 0.001f
    }
}
