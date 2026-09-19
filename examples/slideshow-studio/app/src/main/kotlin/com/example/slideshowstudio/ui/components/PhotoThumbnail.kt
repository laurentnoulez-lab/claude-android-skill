package com.example.slideshowstudio.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.slideshowstudio.data.GalleryPhoto
import com.example.slideshowstudio.data.PhotoRepository

private const val THUMBNAIL_WIDTH = 320

@Composable
fun PhotoThumbnail(
    photo: GalleryPhoto,
    repository: PhotoRepository,
    cache: ThumbnailCache,
    modifier: Modifier = Modifier,
) {
    val image: ImageBitmap? by produceState(initialValue = cache[photo.id], photo.id) {
        if (value != null) return@produceState
        val bitmap = repository.decode(photo, THUMBNAIL_WIDTH)
        if (bitmap != null) {
            val imageBitmap = bitmap.asImageBitmap()
            cache.put(photo.id, imageBitmap)
            value = imageBitmap
        }
    }

    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
