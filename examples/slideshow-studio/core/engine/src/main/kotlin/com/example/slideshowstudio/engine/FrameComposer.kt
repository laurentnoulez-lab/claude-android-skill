package com.example.slideshowstudio.engine

import kotlin.math.floor
import kotlin.math.max

/**
 * Turns a [Storyboard] into frames. This is the single source of truth for what the video looks
 * like: the on-device preview and the 1080p exporter both render the frames it produces, so what
 * the user previews is exactly what gets encoded.
 */
class FrameComposer(
    private val storyboard: Storyboard,
    private val photos: List<PhotoRef>,
) {
    private val settings = storyboard.settings
    private val sceneDuration = storyboard.sceneDurationSeconds
    private val transitionDuration = storyboard.transitionDurationSeconds
    private val canvasAspect = settings.canvasAspect
    private val fadeDuration = minOf(FADE_SECONDS, sceneDuration * 0.25f)

    val totalDurationSeconds: Float = storyboard.totalDurationSeconds
    val frameCount: Int = storyboard.frameCount

    fun frameAt(frameIndex: Int): Frame = compose(frameIndex.toFloat() / settings.fps)

    fun compose(timeSeconds: Float): Frame {
        if (storyboard.isEmpty) {
            return Frame(
                timeSeconds = timeSeconds,
                backgroundColor = settings.backgroundColor,
                backdrops = emptyList(),
                backdropDim = 0f,
                commands = emptyList(),
                blackout = 1f,
            )
        }

        val t = timeSeconds.coerceIn(0f, totalDurationSeconds)
        val sceneIndex = floor(t / sceneDuration).toInt().coerceIn(0, storyboard.scenes.size - 1)
        val scene = storyboard.scenes[sceneIndex]
        val localTime = t - sceneIndex * sceneDuration
        val transition = scene.transitionIn

        val commands = mutableListOf<DrawCommand>()
        val backdrops = mutableListOf<BackdropCommand>()
        val sceneProgress = localTime / sceneDuration
        val backgroundColor: Int
        val backdropDim: Float

        if (sceneIndex > 0 && transition != null && localTime < transitionDuration) {
            val progress = (localTime / transitionDuration).coerceIn(0f, 1f)
            val previous = storyboard.scenes[sceneIndex - 1]
            val outgoingProgress = (t - (sceneIndex - 1) * sceneDuration) / sceneDuration

            // The outgoing scene keeps playing its own movement and stays fully opaque for most of
            // the transition: compositing the incoming scene over it gives a clean cross fade with no
            // luminance dip. It only fades over the tail, once the incoming scene is nearly opaque, so
            // it disappears smoothly instead of popping out from between the new tiles.
            val outgoingAlpha = outgoingFadeAlpha(progress)
            val outgoingTransform = transition.outgoingTransform(progress)

            // The background travels with the scenes: the colour drifts and the backdrops cross fade,
            // so a scene change never flashes through the empty canvas.
            val colourProgress = Easing.SMOOTHER_STEP.apply(progress)
            backgroundColor = Palette.lerpColor(
                previous.background.color,
                scene.background.color,
                colourProgress,
            )
            backdropDim = lerp(previous.background.dim, scene.background.dim, colourProgress)
            appendBackdrop(backdrops, previous, outgoingProgress, outgoingAlpha)
            appendBackdrop(backdrops, scene, sceneProgress, colourProgress)

            appendScene(commands, previous, outgoingProgress) {
                outgoingTransform.copy(alpha = outgoingTransform.alpha * outgoingAlpha)
            }
            appendScene(commands, scene, sceneProgress) { slotIndex ->
                transition.incomingTransform(progress, slotIndex, scene.slots.size)
            }
        } else {
            backgroundColor = scene.background.color
            backdropDim = scene.background.dim
            appendBackdrop(backdrops, scene, sceneProgress, 1f)
            appendScene(commands, scene, sceneProgress) { SceneTransform.Identity }
        }

        return Frame(
            timeSeconds = t,
            backgroundColor = backgroundColor,
            backdrops = backdrops,
            backdropDim = backdropDim,
            commands = commands,
            blackout = blackoutAt(t),
        )
    }

    private fun appendBackdrop(
        target: MutableList<BackdropCommand>,
        scene: Scene,
        progress: Float,
        alpha: Float,
    ) {
        if (alpha <= ALPHA_EPSILON) return
        val background = scene.background
        val photoIndex = background.photoIndex ?: return
        val photo = photos.getOrNull(photoIndex) ?: return
        val motion = background.motion ?: return

        // The zoom is applied to the destination rather than to the crop, so the backdrop keeps
        // covering the whole canvas whichever way it drifts.
        val src = SmartCrop.crop(
            imageAspect = photo.aspect,
            targetAspect = canvasAspect,
            zoom = 1f,
            pan = motion.panAt(progress),
            focus = FocusArea.point(0.5f, 0.5f),
        )
        target += BackdropCommand(
            photoIndex = photoIndex,
            src = src,
            dst = NormRect.Full.scaleAroundCenter(motion.zoomAt(progress)),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }

    private inline fun appendScene(
        target: MutableList<DrawCommand>,
        scene: Scene,
        progress: Float,
        transformForSlot: (Int) -> SceneTransform,
    ) {
        scene.slots.forEachIndexed { slotIndex, slot ->
            val transform = transformForSlot(slotIndex)
            if (transform.alpha <= ALPHA_EPSILON) return@forEachIndexed

            val photo = photos.getOrNull(slot.photoIndex) ?: return@forEachIndexed
            val area = slot.rect
                .scaleAround(0.5f, 0.5f, transform.scale)
                .translate(transform.offset.x, transform.offset.y)
            if (isOffscreen(area)) return@forEachIndexed

            val motion = slot.motion
            val covers = slot.coversSlot
            val pan = motion.panAt(progress)
            val zoom = if (covers) motion.zoomAt(progress).coerceAtMost(slot.maxZoom) else 1f
            // A photo that does not fill its slot cannot be zoomed into without cutting it, so its
            // movement becomes a gentle drift inside the slot instead.
            val displayScale = if (covers) {
                1f
            } else {
                (1f - (motion.zoomAt(progress) - 1f) * 0.5f).coerceIn(0.86f, 0.995f)
            }

            val framing = PhotoFraming.plan(
                photo = photo,
                slot = area,
                canvasAspect = canvasAspect,
                fill = slot.fill,
                zoom = zoom,
                pan = pan,
                displayScale = displayScale,
                displayShift = if (covers) Vec2.Zero else pan,
            )

            val rotation = motion.rotationAt(progress) + transform.rotationDeg
            val dst = if (rotation != 0f && covers) {
                val cover = rotationCoverScale(
                    degrees = rotation,
                    pixelWidth = framing.dst.width * settings.outputWidth,
                    pixelHeight = framing.dst.height * settings.outputHeight,
                )
                framing.dst.scaleAroundCenter(cover)
            } else {
                framing.dst
            }

            target += DrawCommand(
                photoIndex = slot.photoIndex,
                src = framing.src,
                dst = dst,
                // Clipping matters only when the photo is meant to fill its slot edge to edge.
                clip = if (covers) area else null,
                rotationDeg = rotation,
                alpha = transform.alpha,
            )
        }
    }

    /**
     * Opacity of the scene being replaced: 1 while the incoming scene is still translucent, then a
     * fade over the tail of the transition.
     */
    private fun outgoingFadeAlpha(progress: Float): Float {
        if (progress <= OUT_FADE_START) return 1f
        val local = (progress - OUT_FADE_START) / (1f - OUT_FADE_START)
        return Easing.SMOOTHER_STEP.apply(1f - local)
    }

    private fun isOffscreen(rect: NormRect): Boolean =
        rect.right <= 0f || rect.left >= 1f || rect.bottom <= 0f || rect.top >= 1f

    private fun blackoutAt(t: Float): Float {
        if (fadeDuration <= 0f) return 0f
        val fadeIn = if (t < fadeDuration) 1f - t / fadeDuration else 0f
        val fadeOutStart = totalDurationSeconds - fadeDuration
        val fadeOut = if (t > fadeOutStart) (t - fadeOutStart) / fadeDuration else 0f
        return max(fadeIn, fadeOut).coerceIn(0f, 1f)
    }

    private companion object {
        const val ALPHA_EPSILON = 0.002f
        const val FADE_SECONDS = 0.5f
        const val OUT_FADE_START = 0.55f
    }
}
