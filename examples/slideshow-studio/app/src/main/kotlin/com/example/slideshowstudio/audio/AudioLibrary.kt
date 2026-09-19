package com.example.slideshowstudio.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the name and the length of the music files the user picked. */
class AudioLibrary(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun read(uris: List<Uri>): List<AudioTrackInfo> = withContext(dispatcher) {
        uris.mapNotNull { uri -> readTrack(uri) }
    }

    private fun readTrack(uri: Uri): AudioTrackInfo? {
        val durationMs = MediaMetadataRetriever().use { retriever ->
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (error: Exception) {
                Log.w(TAG, "Impossible de lire la durée de $uri", error)
                null
            }
        } ?: return null
        if (durationMs <= 0L) return null

        return AudioTrackInfo(
            uri = uri,
            displayName = displayNameOf(uri),
            durationSeconds = durationMs / 1000f,
        )
    }

    private fun displayNameOf(uri: Uri): String {
        val fromProvider = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (error: Exception) {
            null
        }
        return fromProvider?.substringBeforeLast('.') ?: uri.lastPathSegment ?: "Musique"
    }

    /** [MediaMetadataRetriever] only became [AutoCloseable] on API 29. */
    private inline fun <T> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> T): T = try {
        block(this)
    } finally {
        try {
            release()
        } catch (error: Exception) {
            Log.w(TAG, "Libération du lecteur de métadonnées", error)
        }
    }

    private companion object {
        const val TAG = "AudioLibrary"
    }
}
