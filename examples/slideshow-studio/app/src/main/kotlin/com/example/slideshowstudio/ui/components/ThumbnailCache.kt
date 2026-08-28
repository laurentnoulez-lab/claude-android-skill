package com.example.slideshowstudio.ui.components

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap

/** Small cache so scrolling the photo grid does not decode the same thumbnails again and again. */
class ThumbnailCache(maxEntries: Int = 80) {
    private val cache = LruCache<String, ImageBitmap>(maxEntries)

    operator fun get(id: String): ImageBitmap? = cache.get(id)

    fun put(id: String, image: ImageBitmap) {
        cache.put(id, image)
    }

    fun clear() {
        cache.evictAll()
    }
}
