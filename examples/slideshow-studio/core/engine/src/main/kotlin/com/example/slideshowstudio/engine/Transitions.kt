package com.example.slideshowstudio.engine

import kotlin.random.Random

enum class TransitionFamily { FADE, ZOOM, SLIDE, PUSH, ROTATE, STAGGER }

/** Every transition the engine can play between two scenes. */
enum class TransitionKind(val family: TransitionFamily) {
    CROSS_FADE(TransitionFamily.FADE),
    FADE_ZOOM_IN(TransitionFamily.ZOOM),
    FADE_ZOOM_OUT(TransitionFamily.ZOOM),
    ZOOM_PUSH(TransitionFamily.ZOOM),
    SLIDE_LEFT(TransitionFamily.SLIDE),
    SLIDE_RIGHT(TransitionFamily.SLIDE),
    SLIDE_UP(TransitionFamily.SLIDE),
    SLIDE_DOWN(TransitionFamily.SLIDE),
    PUSH_LEFT(TransitionFamily.PUSH),
    PUSH_RIGHT(TransitionFamily.PUSH),
    PUSH_UP(TransitionFamily.PUSH),
    PUSH_DOWN(TransitionFamily.PUSH),
    ROTATE_FADE(TransitionFamily.ROTATE),
    STAGGER_RISE(TransitionFamily.STAGGER),
    STAGGER_DRIFT(TransitionFamily.STAGGER),
}

/** Transform applied to a whole scene (or to one of its photos when the transition staggers them). */
data class SceneTransform(
    val offset: Vec2 = Vec2.Zero,
    val scale: Float = 1f,
    val alpha: Float = 1f,
    val rotationDeg: Float = 0f,
) {
    val isIdentity: Boolean
        get() = offset.x == 0f && offset.y == 0f && scale == 1f && alpha == 1f && rotationDeg == 0f

    companion object {
        val Identity = SceneTransform()
    }
}

/** Start and end states of a scene during a transition. */
data class SceneMotion(
    val fromOffset: Vec2 = Vec2.Zero,
    val toOffset: Vec2 = Vec2.Zero,
    val fromScale: Float = 1f,
    val toScale: Float = 1f,
    val fromAlpha: Float = 1f,
    val toAlpha: Float = 1f,
    val fromRotationDeg: Float = 0f,
    val toRotationDeg: Float = 0f,
) {
    /**
     * @param motionProgress drives position, scale and rotation, shaped by the transition easing.
     * @param alphaProgress  drives opacity. Fades use their own curve, flat at both ends, so a photo
     *                       never flashes into existence on the first frame of a fast transition.
     */
    fun at(motionProgress: Float, alphaProgress: Float): SceneTransform {
        val p = motionProgress.coerceIn(0f, 1f)
        val a = alphaProgress.coerceIn(0f, 1f)
        return SceneTransform(
            offset = lerp(fromOffset, toOffset, p),
            scale = lerp(fromScale, toScale, p),
            alpha = lerp(fromAlpha, toAlpha, a).coerceIn(0f, 1f),
            rotationDeg = lerp(fromRotationDeg, toRotationDeg, p),
        )
    }

    companion object {
        val Still = SceneMotion()
    }
}

/**
 * A transition between two scenes.
 *
 * The outgoing scene always stays fully opaque: the incoming scene is composited over it. That is
 * what keeps a cross fade perfectly neutral in luminance instead of dipping to the background
 * colour halfway through, which is the classic giveaway of a cheap slideshow.
 *
 * [stagger] spreads the incoming photos in time (0 = all together, 0.4 = strongly sequenced) so a
 * photo that appears in the new scene has its own entrance inside the transition.
 */
data class TransitionSpec(
    val kind: TransitionKind,
    val outgoing: SceneMotion = SceneMotion.Still,
    val incoming: SceneMotion = SceneMotion.Still,
    val stagger: Float = 0f,
    /**
     * Curve of the movement. Slides and pushes keep the sine curve: its peak speed is only 1.6x the
     * average, where a cubic would reach 3x and make photos streak across the frame.
     */
    val easing: Easing = Easing.EASE_IN_OUT_SINE,
) {
    /** Raw (un-eased) progress of the photo at [slotIndex] out of [slotCount]. */
    fun slotProgress(progress: Float, slotIndex: Int, slotCount: Int): Float {
        val p = progress.coerceIn(0f, 1f)
        if (stagger <= 0f || slotCount <= 1) return p
        val span = (stagger * (slotCount - 1)).coerceAtMost(MAX_STAGGER_SPAN)
        val step = span / (slotCount - 1)
        val start = step * slotIndex
        return ((p - start) / (1f - span)).coerceIn(0f, 1f)
    }

    /** Transform of the incoming photo at [slotIndex], including its staggered entrance. */
    fun incomingTransform(progress: Float, slotIndex: Int, slotCount: Int): SceneTransform {
        val raw = slotProgress(progress, slotIndex, slotCount)
        return incoming.at(easing.apply(raw), ALPHA_EASING.apply(raw))
    }

    /** Transform of the scene being replaced. */
    fun outgoingTransform(progress: Float): SceneTransform {
        val p = progress.coerceIn(0f, 1f)
        return outgoing.at(easing.apply(p), ALPHA_EASING.apply(p))
    }

    companion object {
        private val ALPHA_EASING = Easing.SMOOTHER_STEP
        private const val MAX_STAGGER_SPAN = 0.45f
    }
}

object TransitionFactory {

    private val ALL = TransitionKind.entries.toList()

    private val VERTICAL = setOf(
        TransitionKind.SLIDE_UP,
        TransitionKind.SLIDE_DOWN,
        TransitionKind.PUSH_UP,
        TransitionKind.PUSH_DOWN,
        TransitionKind.STAGGER_RISE,
    )

    private val HORIZONTAL = setOf(
        TransitionKind.SLIDE_LEFT,
        TransitionKind.SLIDE_RIGHT,
        TransitionKind.PUSH_LEFT,
        TransitionKind.PUSH_RIGHT,
        TransitionKind.STAGGER_DRIFT,
    )

    /**
     * Transitions that suit a photo the user singled out: they carry the eye to the photo instead of
     * throwing it across the screen. Slides, pushes and staggered entrances are left out.
     */
    private val ELEGANT = listOf(
        TransitionKind.CROSS_FADE,
        TransitionKind.FADE_ZOOM_IN,
        TransitionKind.FADE_ZOOM_OUT,
        TransitionKind.ZOOM_PUSH,
        TransitionKind.ROTATE_FADE,
    )

    /**
     * Picks the next transition, never repeating the previous one and, when possible, avoiding its
     * family as well so two consecutive transitions never feel like the same effect.
     *
     * @param elegantOnly restricts the choice to the calm transitions, used around a photo marked
     *   as important — both the one that brings it in and the one that takes it away.
     *
     * Movement along the long side of the canvas is favoured — vertical in portrait, horizontal in
     * landscape — without ever excluding the other direction.
     */
    fun pickKind(
        random: Random,
        previous: TransitionKind?,
        canvasAspect: Float = DEFAULT_ASPECT,
        elegantOnly: Boolean = false,
    ): TransitionKind {
        val allowed = if (elegantOnly) ELEGANT else ALL
        val candidates = if (previous == null) {
            allowed
        } else {
            allowed.filter { it.family != previous.family }
                .ifEmpty { allowed.filter { it != previous } }
                .ifEmpty { allowed }
        }
        val favoured = if (canvasAspect < 1f) VERTICAL else HORIZONTAL
        val pool = buildList {
            candidates.forEach { kind ->
                add(kind)
                if (kind in favoured) add(kind)
            }
        }
        return pool[random.nextInt(pool.size)]
    }

    private const val DEFAULT_ASPECT = 16f / 9f

    fun create(kind: TransitionKind, random: Random): TransitionSpec {
        val jitter = 0.9f + random.nextFloat() * 0.2f
        return when (kind) {
            TransitionKind.CROSS_FADE -> TransitionSpec(
                kind = kind,
                incoming = SceneMotion(fromAlpha = 0f, toAlpha = 1f, fromScale = 1.015f, toScale = 1f),
                easing = Easing.SMOOTHER_STEP,
            )

            TransitionKind.FADE_ZOOM_IN -> TransitionSpec(
                kind = kind,
                incoming = SceneMotion(fromScale = 1.08f * jitter, toScale = 1f, fromAlpha = 0f, toAlpha = 1f),
                easing = Easing.EASE_OUT_CUBIC,
            )

            TransitionKind.FADE_ZOOM_OUT -> TransitionSpec(
                kind = kind,
                incoming = SceneMotion(fromScale = 0.92f, toScale = 1f, fromAlpha = 0f, toAlpha = 1f),
                easing = Easing.EASE_OUT_CUBIC,
            )

            TransitionKind.ZOOM_PUSH -> TransitionSpec(
                kind = kind,
                outgoing = SceneMotion(fromScale = 1f, toScale = 1.14f),
                incoming = SceneMotion(fromScale = 0.94f, toScale = 1f, fromAlpha = 0f, toAlpha = 1f),
                easing = Easing.EASE_IN_OUT_CUBIC,
            )

            TransitionKind.SLIDE_LEFT -> TransitionSpec(
                kind = kind,
                incoming = SceneMotion(fromOffset = Vec2(1f, 0f)),
            )

            TransitionKind.SLIDE_RIGHT -> TransitionSpec(
                kind = kind,
                incoming = SceneMotion(fromOffset = Vec2(-1f, 0f)),
            )

            TransitionKind.SLIDE_UP -> TransitionSpec(
                kind = kind,
                incoming = SceneMotion(fromOffset = Vec2(0f, 1f)),
            )

            TransitionKind.SLIDE_DOWN -> TransitionSpec(
                kind = kind,
                incoming = SceneMotion(fromOffset = Vec2(0f, -1f)),
            )

            TransitionKind.PUSH_LEFT -> TransitionSpec(
                kind = kind,
                outgoing = SceneMotion(toOffset = Vec2(-1f, 0f)),
                incoming = SceneMotion(fromOffset = Vec2(1f, 0f)),
            )

            TransitionKind.PUSH_RIGHT -> TransitionSpec(
                kind = kind,
                outgoing = SceneMotion(toOffset = Vec2(1f, 0f)),
                incoming = SceneMotion(fromOffset = Vec2(-1f, 0f)),
            )

            TransitionKind.PUSH_UP -> TransitionSpec(
                kind = kind,
                outgoing = SceneMotion(toOffset = Vec2(0f, -1f)),
                incoming = SceneMotion(fromOffset = Vec2(0f, 1f)),
            )

            TransitionKind.PUSH_DOWN -> TransitionSpec(
                kind = kind,
                outgoing = SceneMotion(toOffset = Vec2(0f, 1f)),
                incoming = SceneMotion(fromOffset = Vec2(0f, -1f)),
            )

            TransitionKind.ROTATE_FADE -> TransitionSpec(
                kind = kind,
                incoming = SceneMotion(
                    fromScale = 1.06f,
                    toScale = 1f,
                    fromAlpha = 0f,
                    toAlpha = 1f,
                    fromRotationDeg = if (random.nextBoolean()) 2.2f else -2.2f,
                    toRotationDeg = 0f,
                ),
                easing = Easing.EASE_OUT_CUBIC,
            )

            TransitionKind.STAGGER_RISE -> TransitionSpec(
                kind = kind,
                incoming = SceneMotion(fromOffset = Vec2(0f, 0.22f), fromAlpha = 0f, toAlpha = 1f),
                stagger = 0.3f,
                easing = Easing.EASE_OUT_CUBIC,
            )

            TransitionKind.STAGGER_DRIFT -> TransitionSpec(
                kind = kind,
                incoming = SceneMotion(
                    fromOffset = Vec2(if (random.nextBoolean()) 0.2f else -0.2f, 0f),
                    fromScale = 1.03f,
                    toScale = 1f,
                    fromAlpha = 0f,
                    toAlpha = 1f,
                ),
                stagger = 0.28f,
                easing = Easing.EASE_OUT_CUBIC,
            )
        }
    }
}
