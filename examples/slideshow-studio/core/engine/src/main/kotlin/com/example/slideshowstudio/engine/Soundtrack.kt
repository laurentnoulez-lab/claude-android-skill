package com.example.slideshowstudio.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** How one track gives way to the next. */
enum class TrackTransition {
    /** The next track starts exactly where the previous one stops. */
    CUT,

    /** The tracks overlap: one fades down while the other fades up. */
    CROSSFADE,
}

/** A music file, as the engine sees it: an identity and a length. */
data class AudioTrackRef(
    val id: String,
    val durationSeconds: Float,
)

/** Soundtrack options chosen by the user. */
data class SoundtrackSettings(
    val transition: TrackTransition = TrackTransition.CROSSFADE,
    val crossfadeSeconds: Float = DEFAULT_CROSSFADE,
    val fadeIn: Boolean = true,
    val fadeOut: Boolean = true,
    val fadeSeconds: Float = DEFAULT_FADE,
    val volume: Float = DEFAULT_VOLUME,
) {
    fun sanitized(): SoundtrackSettings = copy(
        crossfadeSeconds = crossfadeSeconds.coerceIn(MIN_CROSSFADE, MAX_CROSSFADE),
        fadeSeconds = fadeSeconds.coerceIn(MIN_FADE, MAX_FADE),
        volume = volume.coerceIn(0f, 1f),
    )

    companion object {
        const val MIN_CROSSFADE = 0.5f
        const val MAX_CROSSFADE = 5f
        const val DEFAULT_CROSSFADE = 2f
        const val MIN_FADE = 0.5f
        const val MAX_FADE = 5f
        const val DEFAULT_FADE = 1.5f
        const val DEFAULT_VOLUME = 0.8f
    }
}

/**
 * One track's slot on the video timeline. Playback always starts at the beginning of the file: the
 * engine trims the end when there is no room left, and never loops a track to fill time.
 */
data class SoundtrackSegment(
    val trackIndex: Int,
    val startSeconds: Float,
    val endSeconds: Float,
    val fadeInSeconds: Float,
    val fadeOutSeconds: Float,
) {
    val durationSeconds: Float get() = endSeconds - startSeconds

    /**
     * Volume envelope at [seconds], between 0 and 1.
     *
     * The ramps follow an equal power curve rather than a straight line: two overlapping tracks are
     * unrelated signals, so their powers add, and a linear crossfade would dip audibly in the
     * middle. With this curve the two gains satisfy `g1² + g2² = 1` throughout the overlap.
     */
    fun gainAt(seconds: Float): Float {
        if (seconds < startSeconds || seconds > endSeconds) return 0f
        val rising = ramp(seconds - startSeconds, fadeInSeconds)
        val falling = ramp(endSeconds - seconds, fadeOutSeconds)
        return min(rising, falling).coerceIn(0f, 1f)
    }

    private fun ramp(elapsed: Float, length: Float): Float {
        if (length <= 0f) return 1f
        val progress = (elapsed / length).coerceIn(0f, 1f)
        return sin(progress * PI.toFloat() / 2f)
    }

    /** Gain of the opposite ramp, used to check that a crossfade keeps a constant power. */
    fun fallingGainAt(seconds: Float): Float {
        if (fadeOutSeconds <= 0f) return if (seconds <= endSeconds) 1f else 0f
        val progress = ((endSeconds - seconds) / fadeOutSeconds).coerceIn(0f, 1f)
        return cos((1f - progress) * PI.toFloat() / 2f)
    }
}

/** The whole soundtrack, laid out against the video. */
data class Soundtrack(
    val segments: List<SoundtrackSegment>,
    val videoDurationSeconds: Float,
    val volume: Float,
) {
    val isEmpty: Boolean get() = segments.isEmpty()

    /** How far into the video the music reaches. */
    val coveredSeconds: Float = segments.maxOfOrNull { it.endSeconds } ?: 0f

    /** How much of the video is left without music. */
    val missingSeconds: Float = (videoDurationSeconds - coveredSeconds).coerceAtLeast(0f)

    val coversWholeVideo: Boolean get() = missingSeconds <= COVERAGE_TOLERANCE

    /** Total gain of every track playing at [seconds], for a quick look at the envelope. */
    fun gainAt(seconds: Float): Float = segments.sumOf { it.gainAt(seconds).toDouble() }.toFloat() * volume

    private companion object {
        const val COVERAGE_TOLERANCE = 0.05f
    }
}

/**
 * Lays the chosen music out along the video.
 *
 * Rules: tracks play in the order the user arranged them, starting at the very beginning of the
 * video; a track that outlasts the video is cut exactly at the end; a track is never looped; and
 * what is missing is reported rather than papered over, so the app can offer to add another track.
 */
object SoundtrackPlanner {

    /** Shorter than this, a slot is not worth starting: it would be heard as a blip. */
    private const val MIN_SEGMENT_SECONDS = 0.25f

    /**
     * Ramp applied at every boundary even when no fade was asked for. Fifteen milliseconds is
     * inaudible, and it is what keeps a cut between two unrelated waveforms from clicking.
     */
    const val ANTI_CLICK_SECONDS = 0.015f

    fun plan(
        tracks: List<AudioTrackRef>,
        videoDurationSeconds: Float,
        rawSettings: SoundtrackSettings,
    ): Soundtrack {
        val settings = rawSettings.sanitized()
        if (tracks.isEmpty() || videoDurationSeconds <= 0f) {
            return Soundtrack(emptyList(), videoDurationSeconds.coerceAtLeast(0f), settings.volume)
        }

        val placed = mutableListOf<SoundtrackSegment>()
        tracks.forEachIndexed { trackIndex, track ->
            if (track.durationSeconds <= MIN_SEGMENT_SECONDS) return@forEachIndexed
            val previous = placed.lastOrNull()
            val start = when {
                previous == null -> 0f
                settings.transition == TrackTransition.CUT -> previous.endSeconds
                else -> previous.endSeconds - overlapFor(previous, track, settings)
            }
            if (start >= videoDurationSeconds - MIN_SEGMENT_SECONDS) return@forEachIndexed

            val end = min(start + track.durationSeconds, videoDurationSeconds)
            if (end - start <= MIN_SEGMENT_SECONDS) return@forEachIndexed
            placed += SoundtrackSegment(
                trackIndex = trackIndex,
                startSeconds = start,
                endSeconds = end,
                fadeInSeconds = ANTI_CLICK_SECONDS,
                fadeOutSeconds = ANTI_CLICK_SECONDS,
            )
        }

        return Soundtrack(
            segments = applyFades(placed, settings),
            videoDurationSeconds = videoDurationSeconds,
            volume = settings.volume,
        )
    }

    /**
     * How long two neighbouring tracks overlap. Never more than half of either of them, so a short
     * track cannot be swallowed whole by the fade it is supposed to arrive through.
     */
    private fun overlapFor(
        previous: SoundtrackSegment,
        next: AudioTrackRef,
        settings: SoundtrackSettings,
    ): Float = minOf(
        settings.crossfadeSeconds,
        previous.durationSeconds * 0.5f,
        next.durationSeconds * 0.5f,
    ).coerceAtLeast(0f)

    private fun applyFades(
        segments: List<SoundtrackSegment>,
        settings: SoundtrackSettings,
    ): List<SoundtrackSegment> {
        if (segments.isEmpty()) return segments
        val result = segments.toMutableList()

        // Each overlap is one track fading down while the next fades up.
        for (index in 0 until result.size - 1) {
            val current = result[index]
            val next = result[index + 1]
            val overlap = (current.endSeconds - next.startSeconds).coerceAtLeast(0f)
            if (overlap > 0f) {
                result[index] = current.copy(fadeOutSeconds = maxOf(current.fadeOutSeconds, overlap))
                result[index + 1] = next.copy(fadeInSeconds = maxOf(next.fadeInSeconds, overlap))
            }
        }

        if (settings.fadeIn) {
            val first = result.first()
            result[0] = first.copy(
                fadeInSeconds = maxOf(first.fadeInSeconds, min(settings.fadeSeconds, first.durationSeconds * 0.5f)),
            )
        }
        if (settings.fadeOut) {
            val last = result.last()
            result[result.lastIndex] = last.copy(
                fadeOutSeconds = maxOf(last.fadeOutSeconds, min(settings.fadeSeconds, last.durationSeconds * 0.5f)),
            )
        }
        return result
    }
}
