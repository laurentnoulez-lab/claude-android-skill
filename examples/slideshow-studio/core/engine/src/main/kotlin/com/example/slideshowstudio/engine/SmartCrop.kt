package com.example.slideshowstudio.engine

/**
 * Computes the visible part of a photo ("cover" cropping).
 *
 * The returned rectangle always has exactly the pixel aspect ratio of the destination slot, so the
 * renderer can map it onto the slot with a single uniform scale: photos are cropped, never stretched.
 */
object SmartCrop {

    /**
     * @param imageAspect  width / height of the source photo, in pixels.
     * @param targetAspect width / height of the destination slot, in pixels.
     * @param zoom         >= 1, how much closer than "just covering" the crop is.
     * @param pan          each component in -1..1, position of the crop inside the room left by the zoom.
     * @param focus        area of the photo that should stay visible (faces, main subject).
     */
    fun crop(
        imageAspect: Float,
        targetAspect: Float,
        zoom: Float,
        pan: Vec2,
        focus: FocusArea,
    ): NormRect {
        val safeZoom = zoom.coerceAtLeast(1f)
        val ratio = targetAspect / imageAspect
        var w: Float
        var h: Float
        if (ratio >= 1f) {
            // Slot is wider than the photo: use the full width, crop top and bottom.
            w = 1f
            h = 1f / ratio
        } else {
            // Slot is taller than the photo: use the full height, crop left and right.
            w = ratio
            h = 1f
        }
        w = (w / safeZoom).coerceIn(0.02f, 1f)
        h = (h / safeZoom).coerceIn(0.02f, 1f)

        val cx = resolveCenter(w, focus.left, focus.right, focus.centerX, pan.x)
        val cy = resolveCenter(h, focus.top, focus.bottom, focus.centerY, pan.y)
        return NormRect.fromCenter(cx, cy, w, h)
    }

    /**
     * Picks the crop center on one axis: stays inside the image, keeps the focus area visible when
     * it fits, and still leaves room for the pan movement.
     */
    private fun resolveCenter(
        size: Float,
        focusStart: Float,
        focusEnd: Float,
        focusCenter: Float,
        pan: Float,
    ): Float {
        val half = size / 2f
        var lo = half
        var hi = 1f - half
        if (hi < lo) {
            return 0.5f
        }
        if (focusEnd - focusStart <= size) {
            // Keeping the whole focus area inside the crop is possible: narrow the allowed range.
            val focusLo = focusEnd - half
            val focusHi = focusStart + half
            val newLo = maxOf(lo, focusLo)
            val newHi = minOf(hi, focusHi)
            if (newLo <= newHi) {
                lo = newLo
                hi = newHi
            }
        }
        val anchor = focusCenter.coerceIn(lo, hi)
        val amplitude = (hi - lo) / 2f
        return (anchor + pan.coerceIn(-1f, 1f) * amplitude).coerceIn(lo, hi)
    }
}
