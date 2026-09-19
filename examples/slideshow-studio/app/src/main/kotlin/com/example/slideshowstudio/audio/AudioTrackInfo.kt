package com.example.slideshowstudio.audio

import android.net.Uri
import com.example.slideshowstudio.engine.AudioTrackRef

/** A music file the user added to the soundtrack. */
data class AudioTrackInfo(
    val uri: Uri,
    val displayName: String,
    val durationSeconds: Float,
) {
    val id: String get() = uri.toString()

    /** What the engine needs to lay the track out on the timeline. */
    fun toRef(): AudioTrackRef = AudioTrackRef(id = id, durationSeconds = durationSeconds)
}
