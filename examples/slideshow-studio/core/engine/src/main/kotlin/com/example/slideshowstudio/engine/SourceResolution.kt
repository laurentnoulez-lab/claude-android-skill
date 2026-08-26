package com.example.slideshowstudio.engine

import kotlin.math.ceil
import kotlin.math.max

/**
 * Works out how many pixels of a photo are actually needed.
 *
 * Decoding every photo at full resolution wastes memory and, worse, forces the renderer to
 * downscale a huge bitmap in one bilinear step, which makes fine details shimmer while the photo
 * moves. Decoding roughly the resolution the photo will be displayed at keeps it sharp and stable.
 */
object SourceResolution {

    /** Extra headroom for the movement that keeps running while the next transition plays. */
    private const val MOTION_HEADROOM = 1.15f

    /** Never ask for less than this: a photo can still be zoomed into during preview scrubbing. */
    private const val MIN_WIDTH = 320

    fun requiredWidth(
        photo: PhotoRef,
        slot: NormRect,
        motion: MotionSpec,
        settings: SlideshowSettings,
        maxWidth: Int,
    ): Int {
        val targetAspect = slot.pixelAspect(settings.canvasAspect)
        val ratio = targetAspect / photo.aspect
        val coverWidth = if (ratio >= 1f) 1f else ratio
        val zoom = max(motion.startZoom, motion.endZoom) * MOTION_HEADROOM
        val visibleWidth = (coverWidth / zoom).coerceIn(0.02f, 1f)
        val slotWidthPx = slot.width * settings.outputWidth
        val needed = ceil(slotWidthPx / visibleWidth).toInt()
        return needed.coerceIn(MIN_WIDTH, minOf(maxWidth, photo.widthPx))
    }

    /**
     * Decoding width for every photo of the storyboard, indexed by photo index. Photos that are not
     * part of any scene are absent from the map.
     */
    fun forStoryboard(
        storyboard: Storyboard,
        photos: List<PhotoRef>,
        maxWidth: Int,
    ): Map<Int, Int> {
        val widths = mutableMapOf<Int, Int>()
        storyboard.scenes.forEach { scene ->
            scene.slots.forEach { slot ->
                val photo = photos.getOrNull(slot.photoIndex) ?: return@forEach
                val width = requiredWidth(photo, slot.rect, slot.motion, storyboard.settings, maxWidth)
                widths[slot.photoIndex] = max(widths[slot.photoIndex] ?: 0, width)
            }
        }
        return widths
    }
}
