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
        if (storyboard.isEmpty) return Frame(timeSeconds, emptyList(), blackout = 1f)

        val t = timeSeconds.coerceIn(0f, totalDurationSeconds)
        val sceneIndex = floor(t / sceneDuration).toInt().coerceIn(0, storyboard.scenes.size - 1)
        val scene = storyboard.scenes[sceneIndex]
        val localTime = t - sceneIndex * sceneDuration
        val transition = scene.transitionIn

        val commands = mutableListOf<DrawCommand>()
        if (sceneIndex > 0 && transition != null && localTime < transitionDuration) {
            val progress = (localTime / transitionDuration).coerceIn(0f, 1f)
            val previous = storyboard.scenes[sceneIndex - 1]

            // The outgoing scene keeps playing its own movement and stays fully opaque for most of
            // the transition: compositing the incoming scene over it gives a clean cross fade with no
            // luminance dip. It only fades over the tail, once the incoming scene is nearly opaque, so
            // it disappears smoothly instead of popping out from between the new tiles.
            val outgoingProgress = (t - (sceneIndex - 1) * sceneDuration) / sceneDuration
            val outgoingAlpha = outgoingFadeAlpha(progress)
            val outgoingTransform = transition.outgoingTransform(progress)
            appendScene(commands, previous, outgoingProgress) {
                outgoingTransform.copy(alpha = outgoingTransform.alpha * outgoingAlpha)
            }

            appendScene(commands, scene, localTime / sceneDuration) { slotIndex ->
                transition.incomingTransform(progress, slotIndex, scene.slots.size)
            }
        } else {
            appendScene(commands, scene, localTime / sceneDuration) { SceneTransform.Identity }
        }

        return Frame(timeSeconds = t, commands = commands, blackout = blackoutAt(t))
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
            val clip = slot.rect
                .scaleAround(0.5f, 0.5f, transform.scale)
                .translate(transform.offset.x, transform.offset.y)
            if (isOffscreen(clip)) return@forEachIndexed

            val slotAspect = slot.rect.pixelAspect(canvasAspect)
            val motion = slot.motion
            val src = SmartCrop.crop(
                imageAspect = photo.aspect,
                targetAspect = slotAspect,
                zoom = motion.zoomAt(progress),
                pan = motion.panAt(progress),
                focus = photo.focus,
            )

            val rotation = motion.rotationAt(progress) + transform.rotationDeg
            val dst = if (rotation == 0f) {
                clip
            } else {
                val cover = rotationCoverScale(
                    degrees = rotation,
                    pixelWidth = clip.width * settings.outputWidth,
                    pixelHeight = clip.height * settings.outputHeight,
                )
                clip.scaleAroundCenter(cover)
            }

            target += DrawCommand(
                photoIndex = slot.photoIndex,
                src = src,
                dst = dst,
                clip = clip,
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
