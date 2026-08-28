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

    /**
     * @param canvasWidthPx width the frame is rendered at, which is the export width for the video
     *   and a smaller value for the preview.
     */
    fun requiredWidth(
        photo: PhotoRef,
        slot: SlotPlan,
        canvasAspect: Float,
        canvasWidthPx: Int,
        maxWidth: Int,
    ): Int {
        val slotAspect = slot.rect.pixelAspect(canvasAspect)
        val cropAspect = PhotoFraming.cropAspect(photo.aspect, slotAspect, slot.fill)
        val (coverWidth, _) = PhotoFraming.coverSize(photo.aspect, cropAspect)
        val zoom = if (slot.coversSlot) {
            max(slot.motion.startZoom, slot.motion.endZoom).coerceAtMost(slot.maxZoom) * MOTION_HEADROOM
        } else {
            1f
        }
        val visibleWidth = (coverWidth / zoom).coerceIn(0.02f, 1f)
        val displayed = PhotoFraming.fitInside(slot.rect, cropAspect, canvasAspect)
        val needed = ceil(displayed.width * canvasWidthPx / visibleWidth).toInt()
        return needed.coerceIn(MIN_WIDTH, minOf(maxWidth, photo.widthPx))
    }

    /**
     * Decoding width for every photo of the storyboard, indexed by photo index. Photos that are not
     * part of any scene are absent from the map.
     */
    fun forStoryboard(
        storyboard: Storyboard,
        photos: List<PhotoRef>,
        canvasWidthPx: Int,
        maxWidth: Int,
    ): Map<Int, Int> {
        val widths = mutableMapOf<Int, Int>()
        storyboard.scenes.forEach { scene ->
            scene.slots.forEach { slot ->
                val photo = photos.getOrNull(slot.photoIndex) ?: return@forEach
                val width = requiredWidth(photo, slot, storyboard.canvasAspect, canvasWidthPx, maxWidth)
                widths[slot.photoIndex] = max(widths[slot.photoIndex] ?: 0, width)
            }
        }
        return widths
    }
}
