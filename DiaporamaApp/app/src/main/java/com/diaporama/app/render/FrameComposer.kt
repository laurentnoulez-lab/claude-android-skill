package com.diaporama.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/** Per-cell slow zoom/pan (Ken Burns) parameters, in normalized units. */
data class KenBurns(
    val zoomStart: Float,
    val zoomEnd: Float,
    val panXStart: Float,
    val panYStart: Float,
    val panXEnd: Float,
    val panYEnd: Float,
)

/**
 * A decoded collage ready to render: the photos, their target rectangles, the
 * Ken Burns motion for each, and a tiny blurred background derived from the
 * first photo.
 */
class Screen(
    val photos: List<Bitmap>,
    val rects: List<RectF>,
    val motions: List<KenBurns>,
    val background: Bitmap,
) {
    fun recycle() {
        photos.forEach { if (!it.isRecycled) it.recycle() }
        if (!background.isRecycled) background.recycle()
    }
}

/**
 * Draws [Screen]s onto 1080p bitmaps. Rendering is deterministic given the same
 * progress value, so the same frame can be re-created for crossfades.
 */
class FrameComposer(
    private val width: Int,
    private val height: Int,
    private val cornerRadius: Float,
) {
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(115, 0, 0, 0) }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private val srcRect = Rect()
    private val bgSrc = Rect()
    private val bgDst = RectF(0f, 0f, width.toFloat(), height.toFloat())

    fun renderScreen(target: Bitmap, screen: Screen, progress: Float) {
        val canvas = Canvas(target)
        canvas.drawColor(Color.rgb(14, 14, 20))

        // Blurred, gently drifting background fill.
        val bg = screen.background
        val drift = 0.04f * progress
        bgSrc.set(
            (bg.width * drift).toInt(),
            (bg.height * drift).toInt(),
            bg.width,
            bg.height,
        )
        canvas.drawBitmap(bg, bgSrc, bgDst, bgPaint)
        canvas.drawRect(bgDst, dimPaint)

        for (i in screen.photos.indices) {
            drawCell(canvas, screen.photos[i], screen.rects[i], screen.motions[i], progress)
        }
    }

    private fun drawCell(
        canvas: Canvas,
        photo: Bitmap,
        dst: RectF,
        kb: KenBurns,
        progress: Float,
    ) {
        val zoom = lerp(kb.zoomStart, kb.zoomEnd, progress)
        val panX = lerp(kb.panXStart, kb.panXEnd, progress)
        val panY = lerp(kb.panYStart, kb.panYEnd, progress)

        val targetAspect = dst.width() / dst.height()
        val iw = photo.width.toFloat()
        val ih = photo.height.toFloat()

        // Center-crop the photo to the cell aspect ratio.
        var cropW: Float
        var cropH: Float
        if (iw / ih > targetAspect) {
            cropH = ih
            cropW = ih * targetAspect
        } else {
            cropW = iw
            cropH = iw / targetAspect
        }
        // Zoom in by shrinking the source crop.
        val zw = cropW / zoom
        val zh = cropH / zoom
        val cx = iw / 2f + panX * (iw - zw) / 2f
        val cy = ih / 2f + panY * (ih - zh) / 2f

        val sl = (cx - zw / 2f).coerceIn(0f, iw - zw)
        val stp = (cy - zh / 2f).coerceIn(0f, ih - zh)
        srcRect.set(
            sl.toInt(),
            stp.toInt(),
            (sl + zw).toInt(),
            (stp + zh).toInt(),
        )

        val save = canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(dst, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        canvas.drawBitmap(photo, srcRect, dst, imagePaint)
        canvas.restoreToCount(save)
    }

    companion object {
        fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        /** Smoothstep easing for pleasant, non-linear crossfades. */
        fun smooth(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }
    }
}
