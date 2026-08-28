package com.example.slideshowstudio.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/** Where a photo is taken from, and where it lands. Both rectangles always share the same aspect. */
data class Framing(val src: NormRect, val dst: NormRect)

/**
 * Fits a photo into a slot.
 *
 * Everything hinges on one number, the *fill*: `1` means the visible part of the photo is cropped
 * until it has exactly the shape of its slot and covers it entirely, `0` means the whole photo stays
 * visible and the background shows around it. In between, the photo is cropped part of the way and
 * letterboxed for the rest — which is what lets the engine take a little from a photo instead of
 * having to choose between cutting a person and leaving a large empty margin.
 *
 * At every value of the fill, source and destination have the same aspect ratio, so the renderer
 * maps one onto the other with a single uniform scale. Stretching a photo is not representable here.
 */
object PhotoFraming {

    /** Above this, the photo covers its slot completely. */
    const val FULL_FILL = 0.999f

    /**
     * Aspect ratio the visible part of the photo will have. Interpolated geometrically so that a
     * fill of 0.5 sits halfway between the photo's shape and the slot's shape in relative terms.
     */
    fun cropAspect(photoAspect: Float, slotAspect: Float, fill: Float): Float {
        val f = fill.coerceIn(0f, 1f)
        return exp(lerp(ln(photoAspect), ln(slotAspect), f))
    }

    /**
     * Largest fill that still leaves room for the whole focus area inside the crop. Cropping past it
     * would start cutting a face.
     */
    fun maxFillKeepingFocus(photo: PhotoRef, slotAspect: Float): Float {
        val b = photo.aspect
        if (slotAspect == b) return 1f
        val focus = photo.focus
        // Cropping towards a wider shape eats the top and bottom; towards a taller shape, the sides.
        val minAspect = if (focus.width > 0f) b * focus.width else 0f
        val maxAspect = if (focus.height > 0f) b / focus.height else Float.MAX_VALUE
        if (minAspect > maxAspect) return 1f
        val allowed = slotAspect.coerceIn(minAspect, maxAspect)
        val fill = ln(allowed / b) / ln(slotAspect / b)
        return fill.coerceIn(0f, 1f)
    }

    /**
     * Zoom ceiling that keeps the focus area inside the crop. Without it a Ken Burns zoom could
     * slowly push a face out of frame in the middle of a scene.
     */
    fun maxZoomKeepingFocus(photo: PhotoRef, cropAspect: Float): Float {
        val (width, height) = coverSize(photo.aspect, cropAspect)
        val focus = photo.focus
        val byWidth = if (focus.width > 0f) width / focus.width else Float.MAX_VALUE
        val byHeight = if (focus.height > 0f) height / focus.height else Float.MAX_VALUE
        return min(byWidth, byHeight).coerceIn(1f, MotionSpec.MAX_ZOOM)
    }

    /**
     * @param fill         how much of the slot the photo covers, see [FULL_FILL].
     * @param zoom         crop zoom, only meaningful at full fill: below it, zooming would cut more.
     * @param pan          crop movement inside the room left by the crop.
     * @param displayScale size of the photo inside its slot, used when it does not cover the slot.
     * @param displayShift position of the photo inside the room left by [displayScale].
     */
    fun plan(
        photo: PhotoRef,
        slot: NormRect,
        canvasAspect: Float,
        fill: Float,
        zoom: Float,
        pan: Vec2,
        displayScale: Float = 1f,
        displayShift: Vec2 = Vec2.Zero,
    ): Framing {
        val slotAspect = slot.pixelAspect(canvasAspect)
        val aspect = cropAspect(photo.aspect, slotAspect, fill)
        val src = SmartCrop.crop(
            imageAspect = photo.aspect,
            targetAspect = aspect,
            zoom = zoom,
            pan = pan,
            focus = photo.focus,
        )
        return Framing(src = src, dst = fitInside(slot, aspect, canvasAspect, displayScale, displayShift))
    }

    /** Largest rectangle of the given pixel aspect that fits inside [slot], scaled and nudged. */
    fun fitInside(
        slot: NormRect,
        pixelAspect: Float,
        canvasAspect: Float,
        displayScale: Float = 1f,
        displayShift: Vec2 = Vec2.Zero,
    ): NormRect {
        var width = slot.width
        var height = width * canvasAspect / pixelAspect
        if (height > slot.height) {
            height = slot.height
            width = height * pixelAspect / canvasAspect
        }
        width *= displayScale
        height *= displayScale
        val slackX = (slot.width - width).coerceAtLeast(0f)
        val slackY = (slot.height - height).coerceAtLeast(0f)
        return NormRect.fromCenter(
            centerX = slot.centerX + displayShift.x.coerceIn(-1f, 1f) * slackX / 2f,
            centerY = slot.centerY + displayShift.y.coerceIn(-1f, 1f) * slackY / 2f,
            width = width,
            height = height,
        )
    }

    /** Normalized size of the crop that just covers [targetAspect], before any zoom. */
    fun coverSize(imageAspect: Float, targetAspect: Float): Pair<Float, Float> {
        val ratio = targetAspect / imageAspect
        return if (ratio >= 1f) 1f to (1f / ratio) else ratio to 1f
    }
}

/**
 * Decides how much of each photo may be cut away.
 *
 * The priority order is the one a person would apply: never distort, then protect the people in the
 * photo, then make a good looking composition, and only then fill the available area.
 */
object CropPlanner {

    fun fillFor(
        mode: CropMode,
        photo: PhotoRef,
        slot: NormRect,
        canvasAspect: Float,
        photosInScene: Int,
    ): Float {
        val slotAspect = slot.pixelAspect(canvasAspect)
        val focusLimit = PhotoFraming.maxFillKeepingFocus(photo, slotAspect)
        return when (mode) {
            CropMode.NEVER -> 0f
            CropMode.SMART -> focusLimit
            CropMode.AUTO -> minOf(automaticFill(photo, slotAspect, photosInScene), focusLimit)
        }
    }

    /**
     * The automatic decision. It is deliberately different from photo to photo: what matters is how
     * much of *this* photo would be lost in *this* slot, and how much room the scene has to spare.
     */
    private fun automaticFill(photo: PhotoRef, slotAspect: Float, photosInScene: Int): Float {
        val (width, height) = PhotoFraming.coverSize(photo.aspect, slotAspect)
        val kept = width * height
        val base = when {
            // Shapes are close: filling the slot costs almost nothing.
            kept >= 0.78f -> 1f
            kept >= 0.60f -> 0.88f
            kept >= 0.42f -> 0.74f
            // A panorama in a tall slot, or a portrait in a wide one: most of it would go, so the
            // photo keeps a margin rather than losing its subject — but it still meets the slot
            // most of the way, otherwise the composition reads as a mistake rather than a choice.
            else -> 0.58f
        }
        // Small tiles read as a mosaic; margins inside them look like mistakes rather than choices.
        val crowding = when {
            photosInScene >= 4 -> 0.25f
            photosInScene == 3 -> 0.18f
            photosInScene == 2 -> 0.08f
            else -> 0f
        }
        return (base + crowding).coerceIn(0f, 1f)
    }
}
