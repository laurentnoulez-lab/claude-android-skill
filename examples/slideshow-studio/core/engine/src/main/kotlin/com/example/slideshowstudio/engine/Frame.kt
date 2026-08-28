package com.example.slideshowstudio.engine

/**
 * One photo to draw. [src] is the visible part of the photo in image space, [dst] where it lands in
 * canvas space, both normalized. [src] and [dst] always share the same pixel aspect ratio, so a
 * renderer maps one onto the other with a uniform scale: photos are never distorted.
 *
 * [rotationDeg] rotates the quad around the center of [dst] in pixel space. When the photo covers
 * its slot, [dst] is already enlarged just enough for the rotated quad to keep covering [clip].
 */
data class DrawCommand(
    val photoIndex: Int,
    val src: NormRect,
    val dst: NormRect,
    val clip: NormRect?,
    val rotationDeg: Float,
    val alpha: Float,
)

/**
 * A photo used as the blurred backdrop of a scene. Drawn before everything else, and expected to be
 * rendered from a heavily blurred, desaturated copy of the photo.
 */
data class BackdropCommand(
    val photoIndex: Int,
    val src: NormRect,
    val dst: NormRect,
    val alpha: Float,
)

/** Everything needed to paint one frame of the video, back to front. */
data class Frame(
    val timeSeconds: Float,
    /** Solid colour filling the canvas before anything else is drawn. */
    val backgroundColor: Int,
    /** Blurred backdrops, at most two while a transition crossfades them. */
    val backdrops: List<BackdropCommand>,
    /** Black veil over the backdrops, so the photos in front stay readable. */
    val backdropDim: Float,
    val commands: List<DrawCommand>,
    /** 0 = normal, 1 = fully black. Used for the opening and closing fades. */
    val blackout: Float = 0f,
)
