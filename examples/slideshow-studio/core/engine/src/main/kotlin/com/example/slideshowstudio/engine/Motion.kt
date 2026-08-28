package com.example.slideshowstudio.engine

import kotlin.random.Random

/** Family of continuous movement applied to a photo while its scene is on screen. */
enum class MotionKind {
    ZOOM_IN,
    ZOOM_OUT,
    PAN_LEFT,
    PAN_RIGHT,
    PAN_UP,
    PAN_DOWN,
    DIAGONAL_UP,
    DIAGONAL_DOWN,
    ROTATE_DRIFT,
}

/**
 * A Ken Burns movement: the crop window travels from (startZoom, startPan) to (endZoom, endPan)
 * over the life of the scene. Nothing is ever perfectly still.
 *
 * Progress may go slightly above 1 while the next transition plays: values are extrapolated so the
 * movement keeps a constant speed instead of freezing under the incoming scene.
 */
data class MotionSpec(
    val kind: MotionKind,
    val startZoom: Float,
    val endZoom: Float,
    val startPan: Vec2,
    val endPan: Vec2,
    val startRotationDeg: Float = 0f,
    val endRotationDeg: Float = 0f,
    val easing: Easing = Easing.LINEAR,
) {
    private fun eased(progress: Float): Float =
        if (progress in 0f..1f) easing.apply(progress) else progress

    fun zoomAt(progress: Float): Float =
        lerp(startZoom, endZoom, eased(progress)).coerceIn(1f, MAX_ZOOM)

    fun panAt(progress: Float): Vec2 {
        val p = eased(progress)
        return Vec2(
            lerp(startPan.x, endPan.x, p).coerceIn(-1f, 1f),
            lerp(startPan.y, endPan.y, p).coerceIn(-1f, 1f),
        )
    }

    fun rotationAt(progress: Float): Float =
        lerp(startRotationDeg, endRotationDeg, eased(progress)).coerceIn(-MAX_ROTATION, MAX_ROTATION)

    companion object {
        const val MAX_ZOOM = 1.35f
        const val MAX_ROTATION = 1.6f
    }
}

/**
 * Builds Ken Burns movements. Amplitudes stay deliberately small: the goal is a photo that breathes,
 * not a photo that runs. Values are picked so that the extrapolation used during transitions never
 * pushes the crop outside of its legal range.
 */
object MotionFactory {

    private val ALL = MotionKind.entries.toList()

    private val VERTICAL = setOf(
        MotionKind.PAN_UP,
        MotionKind.PAN_DOWN,
        MotionKind.DIAGONAL_UP,
        MotionKind.DIAGONAL_DOWN,
    )

    private val HORIZONTAL = setOf(
        MotionKind.PAN_LEFT,
        MotionKind.PAN_RIGHT,
        MotionKind.DIAGONAL_UP,
        MotionKind.DIAGONAL_DOWN,
    )

    /**
     * Picks a movement kind different from the ones in [avoid] whenever possible.
     *
     * A tall canvas leans towards vertical movement and a wide one towards horizontal movement,
     * because that is where the room to travel is. It is a bias, not a rule: zooms and the opposite
     * direction still come up, which is what keeps a long video from feeling mechanical.
     */
    fun pickKind(
        random: Random,
        avoid: Set<MotionKind> = emptySet(),
        canvasAspect: Float = DEFAULT_ASPECT,
    ): MotionKind {
        val candidates = ALL.filterNot { it in avoid }.ifEmpty { ALL }
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

    /**
     * Movement for a photo the user marked as important.
     *
     * Alone on screen and held a little longer, it needs less movement, not more: a slow zoom and a
     * barely perceptible drift, no rotation. The photo is what should be noticed, not the effect.
     */
    fun createHighlight(random: Random, canvasAspect: Float = DEFAULT_ASPECT): MotionSpec {
        val start = 1.03f + random.nextFloat() * 0.03f
        val travel = 0.05f + random.nextFloat() * 0.03f
        val drift = 0.12f + random.nextFloat() * 0.10f
        val zoomIn = random.nextBoolean()
        val vertical = canvasAspect < 1f
        val along = if (random.nextInt(4) == 0) -drift else drift
        return MotionSpec(
            kind = if (zoomIn) MotionKind.ZOOM_IN else MotionKind.ZOOM_OUT,
            startZoom = if (zoomIn) start else start + travel,
            endZoom = if (zoomIn) start + travel else start,
            startPan = if (vertical) Vec2(0f, -along) else Vec2(-along, 0f),
            endPan = if (vertical) Vec2(0f, along) else Vec2(along, 0f),
        )
    }

    /**
     * Movement for a blurred backdrop: slower and larger than a foreground movement, so the backdrop
     * breathes behind the photos without ever competing with them.
     */
    fun createBackdrop(random: Random): MotionSpec {
        val start = 1.05f + random.nextFloat() * 0.05f
        val travel = 0.06f + random.nextFloat() * 0.05f
        val drift = (random.nextFloat() - 0.5f) * 0.3f
        val zoomIn = random.nextBoolean()
        return MotionSpec(
            kind = if (zoomIn) MotionKind.ZOOM_IN else MotionKind.ZOOM_OUT,
            startZoom = if (zoomIn) start else start + travel,
            endZoom = if (zoomIn) start + travel else start,
            startPan = Vec2(drift, -drift),
            endPan = Vec2(-drift, drift),
        )
    }

    fun create(kind: MotionKind, random: Random): MotionSpec {
        val base = 1.04f + random.nextFloat() * 0.04f          // resting zoom, 1.04..1.08
        val travel = 0.06f + random.nextFloat() * 0.05f        // zoom travelled during the scene
        val pan = 0.32f + random.nextFloat() * 0.22f           // pan amplitude, 0.32..0.54
        val drift = (random.nextFloat() - 0.5f) * 0.24f        // small secondary movement
        val tilt = 0.5f + random.nextFloat() * 0.5f            // rotation amplitude in degrees

        return when (kind) {
            MotionKind.ZOOM_IN -> MotionSpec(
                kind = kind,
                startZoom = base,
                endZoom = base + travel,
                startPan = Vec2(drift, -drift),
                endPan = Vec2(-drift, drift),
            )

            MotionKind.ZOOM_OUT -> MotionSpec(
                kind = kind,
                startZoom = base + travel,
                endZoom = base,
                startPan = Vec2(-drift, drift),
                endPan = Vec2(drift, -drift),
            )

            MotionKind.PAN_LEFT -> MotionSpec(
                kind = kind,
                startZoom = base,
                endZoom = base + travel * 0.35f,
                startPan = Vec2(pan, drift),
                endPan = Vec2(-pan, -drift),
            )

            MotionKind.PAN_RIGHT -> MotionSpec(
                kind = kind,
                startZoom = base,
                endZoom = base + travel * 0.35f,
                startPan = Vec2(-pan, -drift),
                endPan = Vec2(pan, drift),
            )

            MotionKind.PAN_UP -> MotionSpec(
                kind = kind,
                startZoom = base,
                endZoom = base + travel * 0.35f,
                startPan = Vec2(drift, pan),
                endPan = Vec2(-drift, -pan),
            )

            MotionKind.PAN_DOWN -> MotionSpec(
                kind = kind,
                startZoom = base,
                endZoom = base + travel * 0.35f,
                startPan = Vec2(-drift, -pan),
                endPan = Vec2(drift, pan),
            )

            MotionKind.DIAGONAL_UP -> MotionSpec(
                kind = kind,
                startZoom = base + travel * 0.5f,
                endZoom = base,
                startPan = Vec2(-pan * 0.8f, pan * 0.8f),
                endPan = Vec2(pan * 0.8f, -pan * 0.8f),
            )

            MotionKind.DIAGONAL_DOWN -> MotionSpec(
                kind = kind,
                startZoom = base,
                endZoom = base + travel * 0.5f,
                startPan = Vec2(-pan * 0.8f, -pan * 0.8f),
                endPan = Vec2(pan * 0.8f, pan * 0.8f),
            )

            MotionKind.ROTATE_DRIFT -> MotionSpec(
                kind = kind,
                startZoom = base + travel * 0.25f,
                endZoom = base + travel * 0.6f,
                startPan = Vec2(drift, drift),
                endPan = Vec2(-drift, -drift),
                startRotationDeg = tilt,
                endRotationDeg = -tilt,
            )
        }
    }
}
