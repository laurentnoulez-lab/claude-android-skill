package com.example.slideshowstudio.engine

/**
 * Region of a photo that matters (typically faces). Expressed in image space (0..1).
 * The cropper does its best to keep this area inside the visible crop at all times.
 */
data class FocusArea(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        /**
         * Default focus when nothing better is known: the center of the frame for landscape and
         * square photos, slightly above center for portrait photos where subjects usually sit.
         */
        fun defaultFor(widthPx: Int, heightPx: Int): FocusArea {
            val portrait = heightPx > widthPx
            val cy = if (portrait) 0.40f else 0.5f
            return FocusArea(0.5f - 0.08f, cy - 0.08f, 0.5f + 0.08f, cy + 0.08f)
        }

        fun point(x: Float, y: Float): FocusArea = FocusArea(x, y, x, y)
    }
}

/**
 * A photo as the engine sees it: identity plus geometry. The engine never touches pixels, which
 * is what keeps it a pure Kotlin module that can be unit tested on the JVM.
 */
data class PhotoRef(
    val id: String,
    val widthPx: Int,
    val heightPx: Int,
    val focus: FocusArea = FocusArea.defaultFor(widthPx, heightPx),
) {
    val aspect: Float get() = widthPx.toFloat() / heightPx.toFloat()
}

/** How many photos a scene may show. The engine always varies the count inside the allowed range. */
enum class ImagesPerSceneMode(val maxImages: Int) {
    SINGLE(1),
    UP_TO_TWO(2),
    UP_TO_THREE(3),
    UP_TO_FOUR(4),
}

/** Rendering + pacing settings chosen by the user. */
data class SlideshowSettings(
    val sceneDurationSeconds: Float = 4f,
    val transitionDurationSeconds: Float = DEFAULT_TRANSITION_SECONDS,
    val mode: ImagesPerSceneMode = ImagesPerSceneMode.UP_TO_THREE,
    val outputWidth: Int = 1920,
    val outputHeight: Int = 1080,
    val fps: Int = 30,
    val seed: Long = 0L,
) {
    /** Aspect ratio of the output frame, 16:9 by default. */
    val canvasAspect: Float get() = outputWidth.toFloat() / outputHeight.toFloat()

    /** Transition duration, never longer than half of a scene so two transitions cannot overlap. */
    val effectiveTransitionSeconds: Float
        get() = transitionDurationSeconds.coerceIn(MIN_TRANSITION_SECONDS, MAX_TRANSITION_SECONDS)
            .coerceAtMost(sceneDurationSeconds * 0.5f)

    fun sanitized(): SlideshowSettings = copy(
        sceneDurationSeconds = sceneDurationSeconds.coerceIn(MIN_SCENE_SECONDS, MAX_SCENE_SECONDS),
        transitionDurationSeconds = transitionDurationSeconds.coerceIn(MIN_TRANSITION_SECONDS, MAX_TRANSITION_SECONDS),
        fps = fps.coerceIn(1, 60),
    )

    companion object {
        const val MIN_SCENE_SECONDS = 2f
        const val MAX_SCENE_SECONDS = 7f
        const val MIN_TRANSITION_SECONDS = 0.5f
        const val MAX_TRANSITION_SECONDS = 1.0f
        const val DEFAULT_TRANSITION_SECONDS = 0.75f
    }
}
