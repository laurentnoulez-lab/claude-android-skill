package com.diaporama.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min

/**
 * Per-cell gentle motion. Because photos are shown fully (never cropped), the
 * "zoom" only breathes between a slightly smaller and the fully-fitted size,
 * and the pan drifts the photo inside the free space that leaves — the whole
 * image therefore stays 100% visible at every frame.
 */
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
 * motion for each, and a tiny blurred background derived from the first photo.
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
    private val dimPaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 24, 34) }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bgSrc = Rect()
    private val bgDst = RectF(0f, 0f, width.toFloat(), height.toFloat())
    private val destF = RectF()

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
        // Rounded "card" the photo sits on.
        canvas.drawRoundRect(dst, cornerRadius, cornerRadius, cardPaint)

        // Padding keeps the photo clear of the rounded corners, so the whole
        // image is visible with no clipping.
        val pad = min(cornerRadius, min(dst.width(), dst.height()) * 0.14f)
        val innerL = dst.left + pad
        val innerT = dst.top + pad
        val innerW = dst.width() - 2 * pad
        val innerH = dst.height() - 2 * pad
        if (innerW <= 0f || innerH <= 0f) return

        val iw = photo.width.toFloat()
        val ih = photo.height.toFloat()

        // Fit the whole photo inside the inner area (contain).
        val fitScale = min(innerW / iw, innerH / ih)
        // Breathe factor is always <= 1, so the photo is never enlarged past fit.
        val breathe = lerp(kb.zoomStart, kb.zoomEnd, progress).coerceIn(0.5f, 1f)
        val scale = fitScale * breathe
        val fw = iw * scale
        val fh = ih * scale

        // Drift inside the leftover space (guarantees full visibility).
        val freeX = innerW - fw
        val freeY = innerH - fh
        val panX = lerp(kb.panXStart, kb.panXEnd, progress)
        val panY = lerp(kb.panYStart, kb.panYEnd, progress)
        val offX = freeX * (0.5f + panX * 0.5f)
        val offY = freeY * (0.5f + panY * 0.5f)

        val l = innerL + offX.coerceIn(0f, freeX)
        val t = innerT + offY.coerceIn(0f, freeY)
        destF.set(l, t, l + fw, t + fh)
        canvas.drawBitmap(photo, null, destF, imagePaint)
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
