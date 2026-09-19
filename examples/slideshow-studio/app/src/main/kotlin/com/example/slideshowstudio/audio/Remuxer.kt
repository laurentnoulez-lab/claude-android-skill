package com.example.slideshowstudio.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Joins the rendered video and the rendered soundtrack into one MP4.
 *
 * Nothing is re-encoded here: the samples of both files are copied across, interleaved by
 * timestamp. Rendering the two streams separately and joining them at the end is what keeps the
 * video pass free of any audio concern — and it means a soundtrack that fails to render simply
 * leaves the silent video untouched.
 */
object Remuxer {

    private const val TAG = "Remuxer"
    private const val MAX_SAMPLE_SIZE = 1 shl 20

    fun combine(video: File, audio: File, output: File): Boolean {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var success = false
        try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)

            val videoTrack = selectTrack(videoExtractor, "video/") ?: return false
            val audioTrack = selectTrack(audioExtractor, "audio/") ?: return false

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoIndex = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val audioIndex = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            muxer.start()

            val buffer = ByteBuffer.allocate(MAX_SAMPLE_SIZE)
            val info = MediaCodec.BufferInfo()
            var videoTime = videoExtractor.sampleTime
            var audioTime = audioExtractor.sampleTime

            // Always write whichever stream is behind, so the file stays playable while streaming.
            while (videoTime >= 0 || audioTime >= 0) {
                val takeVideo = audioTime < 0 || (videoTime in 0..audioTime)
                val extractor = if (takeVideo) videoExtractor else audioExtractor
                val trackIndex = if (takeVideo) videoIndex else audioIndex

                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    if (takeVideo) videoTime = -1 else audioTime = -1
                    continue
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(trackIndex, buffer, info)
                extractor.advance()
                if (takeVideo) videoTime = videoExtractor.sampleTime else audioTime = audioExtractor.sampleTime
            }
            success = true
            return true
        } catch (error: Exception) {
            Log.w(TAG, "Impossible de fusionner l'image et le son", error)
            return false
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            if (success) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!success) output.delete()
        }
    }

    private fun selectTrack(extractor: MediaExtractor, prefix: String): Int? {
        val index = (0 until extractor.trackCount).firstOrNull { track ->
            extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        } ?: return null
        extractor.selectTrack(index)
        return index
    }
}
