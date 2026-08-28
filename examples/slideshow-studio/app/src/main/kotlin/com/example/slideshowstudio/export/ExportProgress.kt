package com.example.slideshowstudio.export

import android.net.Uri
import java.io.File

/** Result of a successful export. */
data class ExportedVideo(
    val file: File,
    val galleryUri: Uri?,
    val width: Int,
    val height: Int,
    val durationSeconds: Float,
)

sealed interface ExportProgress {
    /** Setting up the encoder and the render surface. */
    data object Preparing : ExportProgress

    data class Rendering(val frame: Int, val frameCount: Int) : ExportProgress {
        val fraction: Float get() = if (frameCount <= 0) 0f else frame.toFloat() / frameCount
    }

    /** Writing the file where the system can find it. */
    data object Saving : ExportProgress

    data class Finished(val video: ExportedVideo) : ExportProgress
}
