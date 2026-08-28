package com.example.slideshowstudio.ui.preview

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.slideshowstudio.data.GalleryPhoto
import com.example.slideshowstudio.data.PhotoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Holds the decoded photos the preview needs right now.
 *
 * A slideshow can hold sixty photos; keeping them all decoded would be several hundred megabytes.
 * The store keeps the current scene and the next one, and drops the rest.
 */
@Stable
class PreviewImageStore(
    private val scope: CoroutineScope,
    private val repository: PhotoRepository,
    private val photos: List<GalleryPhoto>,
    private val decodeWidths: Map<Int, Int>,
    private val maxEntries: Int = 12,
) {
    private val images = mutableStateMapOf<Int, ImageBitmap>()
    private val backdrops = mutableStateMapOf<Int, ImageBitmap>()
    private val loading = mutableSetOf<Int>()
    private val loadingBackdrops = mutableSetOf<Int>()
    private val insertionOrder = ArrayDeque<Int>()

    operator fun get(photoIndex: Int): ImageBitmap? = images[photoIndex]

    /** Blurred copy used behind the photos. Small enough that they are all kept in memory. */
    fun backdrop(photoIndex: Int): ImageBitmap? = backdrops[photoIndex]

    fun isReady(photoIndices: Collection<Int>): Boolean = photoIndices.all { images.containsKey(it) }

    /** Loads the blurred backdrops of the given photos. They are tiny, so they are never evicted. */
    fun ensureBackdrops(photoIndices: Set<Int>) {
        photoIndices.forEach { index ->
            if (backdrops.containsKey(index) || index in loadingBackdrops) return@forEach
            val photo = photos.getOrNull(index) ?: return@forEach
            loadingBackdrops += index
            scope.launch {
                val bitmap = repository.decodeBackdrop(photo)
                loadingBackdrops -= index
                if (bitmap != null) backdrops[index] = bitmap.asImageBitmap()
            }
        }
    }

    /** Loads whatever is missing from [photoIndices] and evicts what is no longer needed. */
    fun ensureLoaded(photoIndices: Set<Int>) {
        photoIndices.forEach { index ->
            if (images.containsKey(index) || index in loading) return@forEach
            val photo = photos.getOrNull(index) ?: return@forEach
            loading += index
            scope.launch {
                val bitmap = repository.decode(photo, decodeWidths[index] ?: DEFAULT_WIDTH)
                loading -= index
                if (bitmap != null) {
                    images[index] = bitmap.asImageBitmap()
                    insertionOrder.addLast(index)
                    evict(keep = photoIndices)
                }
            }
        }
    }

    private fun evict(keep: Set<Int>) {
        while (images.size > maxEntries) {
            val victim = insertionOrder.firstOrNull { it !in keep } ?: return
            insertionOrder.remove(victim)
            images.remove(victim)
        }
    }

    private companion object {
        const val DEFAULT_WIDTH = 720
    }
}
