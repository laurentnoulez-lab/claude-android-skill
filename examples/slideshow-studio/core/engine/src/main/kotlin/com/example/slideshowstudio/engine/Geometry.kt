package com.example.slideshowstudio.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.PI

/**
 * A rectangle expressed in normalized coordinates.
 *
 * Two coordinate spaces use this type:
 *  - canvas space: `(0,0)` is the top-left of the video frame, `(1,1)` the bottom-right.
 *  - image space: `(0,0)` is the top-left of a source photo, `(1,1)` the bottom-right.
 *
 * Because both spaces are normalized, a normalized rectangle is *not* square on screen:
 * its pixel aspect ratio is `width / height * containerAspect`.
 */
data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun translate(dx: Float, dy: Float): NormRect =
        NormRect(left + dx, top + dy, right + dx, bottom + dy)

    /** Uniformly scales the rectangle around an arbitrary pivot. */
    fun scaleAround(pivotX: Float, pivotY: Float, factor: Float): NormRect = NormRect(
        left = pivotX + (left - pivotX) * factor,
        top = pivotY + (top - pivotY) * factor,
        right = pivotX + (right - pivotX) * factor,
        bottom = pivotY + (bottom - pivotY) * factor,
    )

    /** Uniformly scales the rectangle around its own center. */
    fun scaleAroundCenter(factor: Float): NormRect = scaleAround(centerX, centerY, factor)

    /** Shrinks the rectangle by [dx] on the left/right edges and [dy] on the top/bottom edges. */
    fun inset(dx: Float, dy: Float): NormRect = NormRect(left + dx, top + dy, right - dx, bottom - dy)

    /** Pixel aspect ratio of this rectangle inside a container whose aspect ratio is [containerAspect]. */
    fun pixelAspect(containerAspect: Float): Float = (width / height) * containerAspect

    companion object {
        val Full = NormRect(0f, 0f, 1f, 1f)

        fun fromCenter(centerX: Float, centerY: Float, width: Float, height: Float): NormRect =
            NormRect(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f)

        fun fromSize(left: Float, top: Float, width: Float, height: Float): NormRect =
            NormRect(left, top, left + width, top + height)
    }
}

/** A 2D vector in normalized units. */
data class Vec2(val x: Float, val y: Float) {
    companion object {
        val Zero = Vec2(0f, 0f)
    }
}

fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

fun lerp(start: Vec2, stop: Vec2, fraction: Float): Vec2 =
    Vec2(lerp(start.x, stop.x, fraction), lerp(start.y, stop.y, fraction))

/**
 * Easing curves. Motion inside a scene stays close to linear so the speed never changes
 * abruptly when a transition starts, while transitions use accelerating/decelerating curves.
 */
enum class Easing {
    LINEAR,
    EASE_IN_OUT_CUBIC,
    EASE_OUT_CUBIC,
    EASE_IN_OUT_SINE,
    SMOOTHER_STEP;

    fun apply(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> x
            EASE_IN_OUT_CUBIC -> if (x < 0.5f) 4f * x * x * x else 1f - pow3(-2f * x + 2f) / 2f
            EASE_OUT_CUBIC -> 1f - pow3(1f - x)
            EASE_IN_OUT_SINE -> (-(cos(PI * x) - 1f) / 2f).toFloat()
            SMOOTHER_STEP -> x * x * x * (x * (x * 6f - 15f) + 10f)
        }
    }

    private fun pow3(v: Float): Float = v * v * v
}

/**
 * Scale factor a rectangle must be enlarged by so that, once rotated by [degrees] around its
 * center, it still covers its original (axis aligned) bounds. Without it a rotated photo would
 * reveal the background in the corners of its slot.
 */
fun rotationCoverScale(degrees: Float, pixelWidth: Float, pixelHeight: Float): Float {
    if (degrees == 0f) return 1f
    val rad = abs(degrees) * PI.toFloat() / 180f
    val c = cos(rad)
    val s = sin(rad)
    val forWidth = c + (pixelHeight / pixelWidth) * s
    val forHeight = c + (pixelWidth / pixelHeight) * s
    return max(1f, max(forWidth, forHeight))
}
