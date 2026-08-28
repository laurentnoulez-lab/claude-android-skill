package com.example.slideshowstudio.engine

/**
 * Region of a photo that matters (typically faces and people). Expressed in image space (0..1).
 * The framing does its best to keep this area fully visible, at every instant of the animation.
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
    /**
     * Marked by the user as one of the photos that matter. It always gets a scene to itself, never
     * shares one with another photo, and is presented with a little more room and a gentler
     * movement. It still appears exactly once, like every other photo.
     */
    val isImportant: Boolean = false,
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

/** Resolution and orientation of the exported video. */
enum class OutputFormat(val width: Int, val height: Int) {
    /** 1920 × 1080, for televisions, computers and YouTube. */
    LANDSCAPE_1080P(1920, 1080),

    /** 1080 × 1920, for phones, Reels, TikTok and Shorts. */
    PORTRAIT_1080P(1080, 1920);

    val aspect: Float get() = width.toFloat() / height.toFloat()
    val isPortrait: Boolean get() = height > width
}

/** How much of a photo the engine is allowed to cut away to fill its slot. */
enum class CropMode {
    /** Never cut: the whole photo stays visible, with background around it if needed. */
    NEVER,

    /** Crop to fill, while keeping faces and people inside the frame. */
    SMART,

    /** Decide photo by photo and scene by scene, between the two above. */
    AUTO,
}

/** How photos are sequenced through the video. */
enum class PhotoOrder {
    /** Exactly the order the user picked. Fully predictable. */
    STRICT,

    /** The user's order, with occasional swaps of one or two positions for better compositions. */
    ADAPTIVE,

    /** Shuffled once, before generation. */
    SHUFFLE,
}

/** What fills the canvas behind and between the photos. */
enum class BackgroundMode {
    /** One colour chosen by the user. */
    SOLID,

    /** A muted colour per scene, drifting from one scene to the next. */
    RANDOM,

    /** One of the scene's own photos, blown up and heavily blurred. */
    BLURRED_PHOTO,
}

/** Rendering + pacing settings chosen by the user. */
data class SlideshowSettings(
    val sceneDurationSeconds: Float = 4f,
    val transitionDurationSeconds: Float = DEFAULT_TRANSITION_SECONDS,
    val mode: ImagesPerSceneMode = ImagesPerSceneMode.UP_TO_THREE,
    val format: OutputFormat = OutputFormat.LANDSCAPE_1080P,
    val cropMode: CropMode = CropMode.AUTO,
    val photoOrder: PhotoOrder = PhotoOrder.ADAPTIVE,
    val backgroundMode: BackgroundMode = BackgroundMode.BLURRED_PHOTO,
    val backgroundColor: Int = Palette.DEFAULT_BACKGROUND,
    val fps: Int = 30,
    val seed: Long = 0L,
) {
    val outputWidth: Int get() = format.width
    val outputHeight: Int get() = format.height

    /** Aspect ratio of the output frame: 16:9 in landscape, 9:16 in portrait. */
    val canvasAspect: Float get() = format.aspect

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
