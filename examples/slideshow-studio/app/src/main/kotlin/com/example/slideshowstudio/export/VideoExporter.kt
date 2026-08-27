package com.example.slideshowstudio.export

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.GLES20
import android.util.Log
import com.example.slideshowstudio.data.GalleryPhoto
import com.example.slideshowstudio.data.PhotoRepository
import com.example.slideshowstudio.engine.FrameComposer
import com.example.slideshowstudio.engine.SourceResolution
import com.example.slideshowstudio.engine.Storyboard
import com.example.slideshowstudio.render.gl.EglCore
import com.example.slideshowstudio.render.gl.GlFrameRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Renders a storyboard into an MP4 file: 1920 × 1080, 30 fps, H.264.
 *
 * Frames are drawn with OpenGL straight into the encoder input surface, so no frame ever travels
 * through the CPU. The whole loop runs on one dedicated thread because an EGL context belongs to the
 * thread that made it current.
 */
class VideoExporter(
    private val context: Context,
    private val photoRepository: PhotoRepository,
) {

    fun export(storyboard: Storyboard, photos: List<GalleryPhoto>): Flow<ExportProgress> = channelFlow {
        require(!storyboard.isEmpty) { "Aucune scène à exporter" }
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "slideshow-export")
        }
        try {
            send(ExportProgress.Preparing)
            val file = withContext(Dispatchers.IO) { VideoStore.createOutputFile(context) }
            val session = withContext(executor.asCoroutineDispatcher()) {
                renderToFile(storyboard, photos, file) { frame, total ->
                    // Reporting every single frame would flood the UI for no benefit.
                    if (frame == total || frame % PROGRESS_STRIDE == 0) {
                        trySendBlocking(ExportProgress.Rendering(frame, total))
                    }
                }
            }
            send(ExportProgress.Saving)
            // Copying the finished file into the gallery is real I/O: never on the caller's thread.
            val galleryUri = withContext(Dispatchers.IO) { VideoStore.publishToGallery(context, file) }
            send(
                ExportProgress.Finished(
                    ExportedVideo(
                        file = file,
                        galleryUri = galleryUri,
                        width = session.width,
                        height = session.height,
                        durationSeconds = storyboard.totalDurationSeconds,
                    ),
                ),
            )
        } finally {
            executor.shutdown()
        }
    }.buffer(Channel.BUFFERED)

    private data class Session(val width: Int, val height: Int)

    private fun CoroutineScope.renderToFile(
        storyboard: Storyboard,
        photos: List<GalleryPhoto>,
        file: File,
        onProgress: (frame: Int, frameCount: Int) -> Unit,
    ): Session {
        val (width, height) = resolveSupportedSize(
            storyboard.settings.outputWidth,
            storyboard.settings.outputHeight,
            storyboard.settings.fps,
        )
        val format = createFormat(width, height, storyboard.settings.fps)
        val codec = createCodec(format)
        var muxer: MediaMuxer? = null
        var eglCore: EglCore? = null
        var renderer: GlFrameRenderer? = null
        val textures = TextureCache()
        var success = false

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()

            eglCore = EglCore(inputSurface).apply { makeCurrent() }
            renderer = GlFrameRenderer(width, height).apply { setUp() }
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val writer = EncoderWriter(codec, muxer)

            val refs = photos.map { it.ref }
            val decodeWidths = SourceResolution.forStoryboard(
                storyboard = storyboard,
                photos = refs,
                canvasWidthPx = width,
                maxWidth = maxTextureSize(),
            )
            val composer = FrameComposer(storyboard, refs)
            val frameCount = composer.frameCount
            val frameDurationNs = 1_000_000_000L / storyboard.settings.fps

            for (frameIndex in 0 until frameCount) {
                ensureActive()
                val frame = composer.frameAt(frameIndex)

                renderer.beginFrame(frame.backgroundColor)

                frame.backdrops.forEach { backdrop ->
                    val photo = photos.getOrNull(backdrop.photoIndex) ?: return@forEach
                    val textureId = textures.textureFor(
                        key = TextureCache.backdropKey(backdrop.photoIndex),
                        frameIndex = frameIndex,
                        renderer = renderer,
                    ) {
                        photoRepository.decodeBackdropSync(photo)
                    }
                    if (textureId != 0) renderer.draw(backdrop, textureId)
                }
                renderer.drawOverlay(frame.backdropDim)

                frame.commands.forEach { command ->
                    val photo = photos.getOrNull(command.photoIndex) ?: return@forEach
                    val textureId = textures.textureFor(
                        key = TextureCache.photoKey(command.photoIndex),
                        frameIndex = frameIndex,
                        renderer = renderer,
                    ) {
                        photoRepository.decodeSync(photo, decodeWidths[command.photoIndex] ?: DEFAULT_DECODE_WIDTH)
                    }
                    if (textureId != 0) renderer.draw(command, textureId)
                }
                renderer.drawOverlay(frame.blackout)

                eglCore.setPresentationTime(frameIndex * frameDurationNs)
                eglCore.swapBuffers()
                writer.drain(endOfStream = false)
                onProgress(frameIndex + 1, frameCount)
            }

            codec.signalEndOfInputStream()
            writer.drain(endOfStream = true)
            writer.finish()
            success = true
            return Session(width, height)
        } finally {
            textures.release(renderer)
            renderer?.release()
            eglCore?.release()
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer?.release() }
            if (!success) file.delete()
        }
    }

    /**
     * Keeps the textures of what is currently on screen, and only those. A photo and its blurred
     * backdrop are two different textures, so they get two different keys.
     */
    private class TextureCache {
        private val textures = HashMap<Int, Int>()
        private val lastUsed = HashMap<Int, Int>()

        fun textureFor(
            key: Int,
            frameIndex: Int,
            renderer: GlFrameRenderer,
            decode: () -> Bitmap?,
        ): Int {
            lastUsed[key] = frameIndex
            textures[key]?.let { return it }
            evictUnused(frameIndex, renderer)
            val bitmap = decode() ?: return 0
            val textureId = try {
                renderer.createTexture(bitmap)
            } finally {
                bitmap.recycle()
            }
            textures[key] = textureId
            return textureId
        }

        fun evictUnused(frameIndex: Int, renderer: GlFrameRenderer) {
            if (textures.size < MAX_TEXTURES) return
            val stale = textures.keys.filter { (lastUsed[it] ?: 0) < frameIndex }
            stale.forEach { key ->
                textures.remove(key)?.let(renderer::deleteTexture)
                lastUsed.remove(key)
            }
        }

        fun release(renderer: GlFrameRenderer?) {
            renderer?.let { textures.values.forEach(it::deleteTexture) }
            textures.clear()
            lastUsed.clear()
        }

        companion object {
            private const val MAX_TEXTURES = 12

            fun photoKey(photoIndex: Int): Int = photoIndex * 2
            fun backdropKey(photoIndex: Int): Int = photoIndex * 2 + 1
        }
    }

    /** Pulls encoded samples out of the codec and writes them to the muxer. */
    private class EncoderWriter(
        private val codec: MediaCodec,
        private val muxer: MediaMuxer,
    ) {
        private val bufferInfo = MediaCodec.BufferInfo()
        private var trackIndex = -1
        private var started = false

        fun drain(endOfStream: Boolean) {
            var guard = 0
            // Between frames we only collect what is already encoded; waiting there would add up to
            // several seconds over a whole video.
            val timeoutUs = if (endOfStream) END_OF_STREAM_TIMEOUT_US else 0L
            while (true) {
                val index = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                        if (++guard > MAX_SPINS) error("L'encodeur ne répond plus")
                    }

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!started) { "Le format de sortie a changé deux fois" }
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    }

                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // Codec configuration travels in the track format, not as a sample.
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && buffer != null && started) {
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        fun finish() {
            if (started) {
                muxer.stop()
                started = false
            }
        }

        private companion object {
            const val END_OF_STREAM_TIMEOUT_US = 10_000L
            const val MAX_SPINS = 500
        }
    }

    private fun createFormat(width: Int, height: Int, fps: Int): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRateFor(width, height))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }

    private fun createCodec(format: MediaFormat): MediaCodec {
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        return if (name != null) {
            MediaCodec.createByCodecName(name)
        } else {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }
    }

    /**
     * Falls back to a smaller frame when the device has no encoder for the requested size. The
     * engine works in normalized coordinates, so the composition is identical, only smaller.
     */
    private fun resolveSupportedSize(width: Int, height: Int, fps: Int): Pair<Int, Int> {
        // Fallbacks keep the orientation the user picked: a portrait video never falls back to a
        // landscape frame.
        val fallbacks = if (height > width) {
            listOf(720 to 1280, 480 to 854)
        } else {
            listOf(1280 to 720, 854 to 480)
        }
        val candidates = listOf(width to height) + fallbacks
        candidates.forEach { (candidateWidth, candidateHeight) ->
            val format = createFormat(candidateWidth, candidateHeight, fps)
            if (MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null) {
                if (candidateWidth != width) {
                    Log.w(TAG, "Encodeur ${width}x$height indisponible, repli sur ${candidateWidth}x$candidateHeight")
                }
                return candidateWidth to candidateHeight
            }
        }
        return width to height
    }

    private fun maxTextureSize(): Int {
        val value = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, value, 0)
        return value[0].coerceIn(1024, 4096)
    }

    private fun bitRateFor(width: Int, height: Int): Int =
        (width * height * BITS_PER_PIXEL_PER_FRAME).toInt().coerceIn(4_000_000, 24_000_000)

    private companion object {
        const val TAG = "VideoExporter"
        const val I_FRAME_INTERVAL_SECONDS = 1
        const val DEFAULT_DECODE_WIDTH = 1920
        const val BITS_PER_PIXEL_PER_FRAME = 6.0
        const val PROGRESS_STRIDE = 6
    }
}
