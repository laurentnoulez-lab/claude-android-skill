package com.diaporama.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import com.diaporama.app.video.BitmapTextureRenderer
import com.diaporama.app.video.InputSurface
import com.diaporama.app.video.VideoEncoder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/** User-tunable slideshow settings. */
data class SlideshowConfig(
    val photosPerScreen: Int = 3,
    val secondsPerScreen: Float = 3.5f,
    val transitionSeconds: Float = 1.0f,
    val fps: Int = 30,
    val width: Int = 1920,
    val height: Int = 1080,
    val bitRate: Int = 16_000_000,
)

/**
 * Turns a list of photo [Uri]s into a smooth 1080p MP4 slideshow. Photos are
 * grouped into collages ("screens"); each screen holds with a slow Ken Burns
 * motion and crossfades into the next.
 */
class SlideshowBuilder(
    private val context: Context,
    private val config: SlideshowConfig,
) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() = cancelled.set(true)

    /**
     * Renders the slideshow to [outputFile]. [onProgress] receives a value in
     * 0f..1f. Returns true on success, false if cancelled.
     */
    fun build(photoUris: List<Uri>, outputFile: File, onProgress: (Float) -> Unit): Boolean {
        require(photoUris.isNotEmpty()) { "No photos provided" }

        val groupSize = config.photosPerScreen.coerceIn(1, 6)
        val screensUris = photoUris.chunked(groupSize)
        val numScreens = screensUris.size

        val holdFrames = max(1, (config.secondsPerScreen * config.fps).roundToInt())
        val transFrames = if (numScreens > 1) {
            max(1, (config.transitionSeconds * config.fps).roundToInt())
        } else {
            0
        }
        val perScreen = holdFrames + transFrames
        val totalFrames = numScreens * holdFrames + (numScreens - 1) * transFrames

        val composer = FrameComposer(config.width, config.height, cornerRadius = 28f)

        val frameBitmap = Bitmap.createBitmap(config.width, config.height, Bitmap.Config.ARGB_8888)
        val tmpA = Bitmap.createBitmap(config.width, config.height, Bitmap.Config.ARGB_8888)
        val tmpB = Bitmap.createBitmap(config.width, config.height, Bitmap.Config.ARGB_8888)
        val frameCanvas = Canvas(frameBitmap)
        val crossfadePaint = Paint(Paint.FILTER_BITMAP_FLAG)

        val screenCache = HashMap<Int, Screen>()

        val encoder = VideoEncoder(
            config.width, config.height, config.bitRate, config.fps, outputFile,
        )
        val inputSurface = InputSurface(encoder.inputSurface)
        inputSurface.makeCurrent()
        val renderer = BitmapTextureRenderer().apply { setup() }

        try {
            for (g in 0 until totalFrames) {
                if (cancelled.get()) return false

                val holdStart = { s: Int -> s * perScreen }
                // Which screen index owns a hold covering g, or -1.
                val sInHold = g / perScreen
                val offsetInScreen = g - holdStart(sInHold)

                if (offsetInScreen < holdFrames || sInHold == numScreens - 1) {
                    // Steady hold on screen sInHold.
                    val screen = ensureScreen(sInHold, screensUris, groupSize, screenCache)
                    val p = kenBurnsProgress(sInHold, g, numScreens, holdFrames, transFrames)
                    composer.renderScreen(frameBitmap, screen, p)
                    freeUnused(screenCache, setOf(sInHold))
                } else {
                    // Crossfade from screen sInHold to sInHold+1.
                    val next = sInHold + 1
                    val screenA = ensureScreen(sInHold, screensUris, groupSize, screenCache)
                    val screenB = ensureScreen(next, screensUris, groupSize, screenCache)
                    val tt = (offsetInScreen - holdFrames).toFloat() / transFrames
                    val alpha = FrameComposer.smooth(tt)

                    composer.renderScreen(
                        tmpA, screenA,
                        kenBurnsProgress(sInHold, g, numScreens, holdFrames, transFrames),
                    )
                    composer.renderScreen(
                        tmpB, screenB,
                        kenBurnsProgress(next, g, numScreens, holdFrames, transFrames),
                    )
                    frameCanvas.drawColor(Color.BLACK)
                    crossfadePaint.alpha = 255
                    frameCanvas.drawBitmap(tmpA, 0f, 0f, crossfadePaint)
                    crossfadePaint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
                    frameCanvas.drawBitmap(tmpB, 0f, 0f, crossfadePaint)
                    freeUnused(screenCache, setOf(sInHold, next))
                }

                renderer.drawFrame(frameBitmap)
                inputSurface.setPresentationTime(g * 1_000_000_000L / config.fps)
                inputSurface.swapBuffers()
                encoder.drainEncoder(false)

                if (g % 10 == 0 || g == totalFrames - 1) {
                    onProgress((g + 1).toFloat() / totalFrames)
                }
            }

            encoder.drainEncoder(true)
            onProgress(1f)
            return true
        } finally {
            renderer.release()
            inputSurface.release()
            encoder.release()
            screenCache.values.forEach { it.recycle() }
            screenCache.clear()
            frameBitmap.recycle()
            tmpA.recycle()
            tmpB.recycle()
        }
    }

    /** Ken Burns progress (0..1) for [screen] at global frame [g]. */
    private fun kenBurnsProgress(
        screen: Int,
        g: Int,
        numScreens: Int,
        holdFrames: Int,
        transFrames: Int,
    ): Float {
        val perScreen = holdFrames + transFrames
        val lifeStart = if (screen == 0) 0 else screen * perScreen - transFrames
        val holdEnd = screen * perScreen + holdFrames
        val lifeEnd = if (screen == numScreens - 1) holdEnd else holdEnd + transFrames
        val span = (lifeEnd - lifeStart).coerceAtLeast(1)
        return ((g - lifeStart).toFloat() / span).coerceIn(0f, 1f)
    }

    private fun ensureScreen(
        index: Int,
        screensUris: List<List<Uri>>,
        groupSize: Int,
        cache: HashMap<Int, Screen>,
    ): Screen = cache.getOrPut(index) { prepareScreen(screensUris[index], index) }

    private fun freeUnused(cache: HashMap<Int, Screen>, keep: Set<Int>) {
        val it = cache.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key !in keep) {
                entry.value.recycle()
                it.remove()
            }
        }
    }

    private fun prepareScreen(uris: List<Uri>, seed: Int): Screen {
        val count = uris.size
        val rects = Layouts.forCount(count, config.width, config.height, margin = 40f, gap = 16f)
        val random = Random(seed * 977 + 13)

        val photos = uris.mapIndexed { i, uri ->
            val rect = rects[i]
            val target = (max(rect.width(), rect.height()) * 1.3f).roundToInt().coerceAtLeast(512)
            decodeScaled(uri, target)
        }
        val motions = List(count) { generateMotion(random) }

        val first = photos.first()
        val background = Bitmap.createScaledBitmap(first, 64, 36, true)

        return Screen(photos, rects.take(count), motions, background)
    }

    private fun generateMotion(random: Random): KenBurns {
        val zoomIn = random.nextBoolean()
        val delta = 0.06f + random.nextFloat() * 0.05f
        val zStart = if (zoomIn) 1.0f else 1.0f + delta
        val zEnd = if (zoomIn) 1.0f + delta else 1.0f
        fun pan() = (random.nextFloat() * 2f - 1f) * 0.6f
        return KenBurns(
            zoomStart = zStart,
            zoomEnd = zEnd,
            panXStart = pan(),
            panYStart = pan(),
            panXEnd = pan(),
            panYEnd = pan(),
        )
    }

    private fun decodeScaled(uri: Uri, maxDim: Int): Bitmap {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val size = info.size
                val longSide = max(size.width, size.height)
                if (longSide > maxDim) {
                    val scale = maxDim.toFloat() / longSide
                    decoder.setTargetSize(
                        max(1, (size.width * scale).roundToInt()),
                        max(1, (size.height * scale).roundToInt()),
                    )
                }
            }
        } catch (e: Exception) {
            // Fallback so a single unreadable image can't abort the whole render.
            Bitmap.createBitmap(maxDim, maxDim, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(40, 40, 52))
            }
        }
    }
}
