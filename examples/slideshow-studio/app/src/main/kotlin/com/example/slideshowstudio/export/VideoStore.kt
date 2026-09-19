package com.example.slideshowstudio.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where exported videos are written, and how they reach the gallery and the share sheet. */
object VideoStore {

    private const val TAG = "VideoStore"
    private val RELATIVE_PATH = Environment.DIRECTORY_MOVIES + "/Diaporama Studio"

    fun createOutputFile(context: Context): File {
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "exports",
        )
        directory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(directory, "diaporama-$stamp.mp4")
    }

    /**
     * Copies the finished video where the gallery can see it. Only on Android 10 and later, where
     * this needs no permission; below that the file stays in the app folder and can still be shared.
     */
    fun publishToGallery(context: Context, file: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: return null
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (error: Exception) {
            Log.w(TAG, "Impossible d'ajouter la vidéo à la galerie", error)
            null
        }
    }

    /** URI other apps can read, for the share sheet or an external player. */
    fun shareableUri(context: Context, video: ExportedVideo): Uri = video.galleryUri
        ?: FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", video.file)
}
