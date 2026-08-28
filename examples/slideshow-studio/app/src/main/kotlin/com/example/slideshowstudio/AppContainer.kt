package com.example.slideshowstudio

import android.content.Context
import com.example.slideshowstudio.data.AndroidPhotoRepository
import com.example.slideshowstudio.data.PhotoRepository
import com.example.slideshowstudio.export.VideoExporter
import com.example.slideshowstudio.ui.components.ThumbnailCache

/**
 * Dependencies of the app, wired by hand.
 *
 * There is no database, no network and a single screen graph here, so a container is all the
 * injection this app needs; a full DI framework would be more ceremony than value.
 */
class AppContainer(context: Context) {
    private val applicationContext: Context = context.applicationContext

    val photoRepository: PhotoRepository = AndroidPhotoRepository(applicationContext)
    val videoExporter: VideoExporter = VideoExporter(applicationContext, photoRepository)
    val thumbnailCache: ThumbnailCache = ThumbnailCache()
}
