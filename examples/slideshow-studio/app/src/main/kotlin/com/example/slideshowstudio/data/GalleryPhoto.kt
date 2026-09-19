package com.example.slideshowstudio.data

import android.net.Uri
import com.example.slideshowstudio.engine.PhotoRef

/**
 * A photo picked by the user. [ref] is what the engine works with: dimensions and focus area only,
 * never pixels.
 */
data class GalleryPhoto(
    val uri: Uri,
    val ref: PhotoRef,
) {
    val id: String get() = ref.id
}
