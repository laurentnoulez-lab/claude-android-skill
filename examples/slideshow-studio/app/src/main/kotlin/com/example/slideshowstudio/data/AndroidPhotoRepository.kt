package com.example.slideshowstudio.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.slideshowstudio.engine.FocusArea
import com.example.slideshowstudio.engine.PhotoRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPhotoRepository(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PhotoRepository {

    override suspend fun loadPhotos(uris: List<Uri>): List<GalleryPhoto> = withContext(dispatcher) {
        uris.mapNotNull { uri ->
            val size = BitmapDecoder.readSize(context, uri) ?: return@mapNotNull null
            GalleryPhoto(
                uri = uri,
                ref = PhotoRef(
                    id = uri.toString(),
                    widthPx = size.width,
                    heightPx = size.height,
                    focus = detectFocus(uri, size),
                ),
            )
        }
    }

    override suspend fun decode(photo: GalleryPhoto, targetWidth: Int): Bitmap? = withContext(dispatcher) {
        decodeSync(photo, targetWidth)
    }

    override fun decodeSync(photo: GalleryPhoto, targetWidth: Int): Bitmap? =
        BitmapDecoder.decode(context, photo.uri, targetWidth)

    /**
     * Faces are looked for on a thumbnail: cheap enough to run on every imported photo, and precise
     * enough to steer the crop away from cutting someone's head.
     */
    private fun detectFocus(uri: Uri, size: PhotoSize): FocusArea {
        val thumbnail = BitmapDecoder.decode(context, uri, FOCUS_ANALYSIS_WIDTH)
            ?: return FocusArea.defaultFor(size.width, size.height)
        return try {
            FaceFocusDetector.detect(thumbnail) ?: FocusArea.defaultFor(size.width, size.height)
        } finally {
            thumbnail.recycle()
        }
    }

    private companion object {
        const val FOCUS_ANALYSIS_WIDTH = 512
    }
}
