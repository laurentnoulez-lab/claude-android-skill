package com.example.slideshowstudio.engine

/**
 * One photo to draw. [src] is the visible part of the photo in image space, [dst] where it lands in
 * canvas space, both normalized. [src] and [dst] always share the same pixel aspect ratio, so a
 * renderer maps one onto the other with a uniform scale: photos are never distorted.
 *
 * [rotationDeg] rotates the quad around the center of [dst] in pixel space; [dst] is already
 * enlarged just enough for the rotated quad to keep covering [clip].
 */
data class DrawCommand(
    val photoIndex: Int,
    val src: NormRect,
    val dst: NormRect,
    val clip: NormRect?,
    val rotationDeg: Float,
    val alpha: Float,
)

/** Everything needed to paint one frame of the video. Commands are drawn back to front. */
data class Frame(
    val timeSeconds: Float,
    val commands: List<DrawCommand>,
    /** 0 = normal, 1 = fully black. Used for the opening and closing fades. */
    val blackout: Float = 0f,
)
