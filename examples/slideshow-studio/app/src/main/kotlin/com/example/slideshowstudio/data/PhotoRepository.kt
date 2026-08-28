package com.example.slideshowstudio.data

import android.graphics.Bitmap
import android.net.Uri

/** Access to the photos the user picked. */
interface PhotoRepository {

    /**
     * Reads the dimensions and the focus area of each URI. Unreadable URIs are dropped rather than
     * failing the whole import.
     */
    suspend fun loadPhotos(uris: List<Uri>): List<GalleryPhoto>

    /** Decodes a photo at roughly [targetWidth] pixels wide, EXIF orientation applied. */
    suspend fun decode(photo: GalleryPhoto, targetWidth: Int): Bitmap?

    /**
     * Blocking version of [decode]. The exporter needs it: its render loop owns an EGL context bound
     * to one thread, and must not be moved to another thread by a suspension point. Never call this
     * from the main thread.
     */
    fun decodeSync(photo: GalleryPhoto, targetWidth: Int): Bitmap?

    /** Blurred, desaturated copy of a photo, used as the backdrop of a scene. */
    suspend fun decodeBackdrop(photo: GalleryPhoto): Bitmap?

    /** Blocking version of [decodeBackdrop], for the exporter's render thread. */
    fun decodeBackdropSync(photo: GalleryPhoto): Bitmap?
}
