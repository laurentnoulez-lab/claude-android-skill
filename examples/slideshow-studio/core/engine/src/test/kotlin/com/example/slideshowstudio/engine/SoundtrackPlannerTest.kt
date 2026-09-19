package com.example.slideshowstudio.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundtrackPlannerTest {

    private fun track(id: String, seconds: Float) = AudioTrackRef(id, seconds)

    private val crossfade = SoundtrackSettings(
        transition = TrackTransition.CROSSFADE,
        crossfadeSeconds = 2f,
        fadeIn = false,
        fadeOut = false,
    )

    private val cut = SoundtrackSettings(transition = TrackTransition.CUT, fadeIn = false, fadeOut = false)

    @Test
    fun `no music means an empty soundtrack that reports the whole video as missing`() {
        val soundtrack = SoundtrackPlanner.plan(emptyList(), 60f, SoundtrackSettings())
        assertTrue(soundtrack.isEmpty)
        assertEquals(60f, soundtrack.missingSeconds)
        assertTrue(!soundtrack.coversWholeVideo)
    }

    @Test
    fun `a track longer than the video is cut exactly at the end`() {
        val soundtrack = SoundtrackPlanner.plan(listOf(track("a", 300f)), 42f, cut)
        val segment = soundtrack.segments.single()
        assertEquals(0f, segment.startSeconds)
        assertEquals(42f, segment.endSeconds)
        assertTrue(soundtrack.coversWholeVideo)
        assertEquals(0f, soundtrack.missingSeconds)
    }

    @Test
    fun `a track shorter than the video reports what is missing`() {
        val soundtrack = SoundtrackPlanner.plan(listOf(track("a", 30f)), 100f, cut)
        assertEquals(30f, soundtrack.coveredSeconds)
        assertEquals(70f, soundtrack.missingSeconds)
        assertTrue(!soundtrack.coversWholeVideo)
    }

    @Test
    fun `a track is never looped to fill the video`() {
        val tracks = listOf(track("a", 10f), track("b", 12f))
        val soundtrack = SoundtrackPlanner.plan(tracks, 600f, cut)
        assertEquals(2, soundtrack.segments.size)
        val played = soundtrack.segments.sumOf { it.durationSeconds.toDouble() }.toFloat()
        assertTrue(played <= 22f + 1e-3f, "played $played seconds out of 22 available")
    }

    @Test
    fun `cutting places the tracks back to back`() {
        val tracks = listOf(track("a", 20f), track("b", 20f), track("c", 20f))
        val soundtrack = SoundtrackPlanner.plan(tracks, 100f, cut)
        assertEquals(3, soundtrack.segments.size)
        soundtrack.segments.zipWithNext().forEach { (first, second) ->
            assertEquals(first.endSeconds, second.startSeconds, 1e-3f)
        }
        assertEquals(0f, soundtrack.segments.first().startSeconds)
        assertEquals(60f, soundtrack.coveredSeconds, 1e-3f)
    }

    @Test
    fun `crossfading overlaps the tracks by the chosen duration`() {
        val tracks = listOf(track("a", 20f), track("b", 20f))
        val soundtrack = SoundtrackPlanner.plan(tracks, 100f, crossfade)
        val (first, second) = soundtrack.segments
        assertEquals(2f, first.endSeconds - second.startSeconds, 1e-3f)
        assertEquals(2f, first.fadeOutSeconds, 1e-3f)
        assertEquals(2f, second.fadeInSeconds, 1e-3f)
    }

    @Test
    fun `a crossfade never swallows a short track`() {
        val tracks = listOf(track("long", 30f), track("short", 3f), track("long2", 30f))
        val soundtrack = SoundtrackPlanner.plan(tracks, 200f, crossfade.copy(crossfadeSeconds = 5f))
        soundtrack.segments.forEach { segment ->
            assertTrue(
                segment.fadeInSeconds <= segment.durationSeconds / 2f + 1e-3f,
                "fade in ${segment.fadeInSeconds} for ${segment.durationSeconds}s",
            )
            assertTrue(
                segment.fadeOutSeconds <= segment.durationSeconds / 2f + 1e-3f,
                "fade out ${segment.fadeOutSeconds} for ${segment.durationSeconds}s",
            )
        }
    }

    @Test
    fun `a crossfade keeps a constant power through the overlap`() {
        val tracks = listOf(track("a", 20f), track("b", 20f))
        val soundtrack = SoundtrackPlanner.plan(tracks, 100f, crossfade)
        val (first, second) = soundtrack.segments
        var t = second.startSeconds
        while (t <= first.endSeconds) {
            val power = first.gainAt(t) * first.gainAt(t) + second.gainAt(t) * second.gainAt(t)
            assertTrue(abs(power - 1f) < 0.05f, "power $power at $t")
            t += 0.05f
        }
    }

    @Test
    fun `fades in and out can be turned on independently`() {
        val tracks = listOf(track("a", 60f))
        val both = SoundtrackPlanner.plan(tracks, 50f, SoundtrackSettings(fadeIn = true, fadeOut = true, fadeSeconds = 2f))
        assertEquals(2f, both.segments.single().fadeInSeconds, 1e-3f)
        assertEquals(2f, both.segments.single().fadeOutSeconds, 1e-3f)

        val onlyIn = SoundtrackPlanner.plan(tracks, 50f, SoundtrackSettings(fadeIn = true, fadeOut = false, fadeSeconds = 2f))
        assertEquals(2f, onlyIn.segments.single().fadeInSeconds, 1e-3f)
        assertEquals(SoundtrackPlanner.ANTI_CLICK_SECONDS, onlyIn.segments.single().fadeOutSeconds, 1e-4f)

        val none = SoundtrackPlanner.plan(tracks, 50f, SoundtrackSettings(fadeIn = false, fadeOut = false))
        assertEquals(SoundtrackPlanner.ANTI_CLICK_SECONDS, none.segments.single().fadeInSeconds, 1e-4f)
    }

    @Test
    fun `every boundary carries at least an anti click ramp`() {
        val tracks = listOf(track("a", 20f), track("b", 20f))
        val soundtrack = SoundtrackPlanner.plan(tracks, 100f, cut)
        soundtrack.segments.forEach { segment ->
            assertTrue(segment.fadeInSeconds > 0f, "a boundary would click")
            assertTrue(segment.fadeOutSeconds > 0f, "a boundary would click")
            assertEquals(0f, segment.gainAt(segment.startSeconds), 1e-4f)
            assertEquals(0f, segment.gainAt(segment.endSeconds), 1e-4f)
        }
    }

    @Test
    fun `music never plays past the end of the video`() {
        val tracks = listOf(track("a", 40f), track("b", 40f), track("c", 40f))
        val soundtrack = SoundtrackPlanner.plan(tracks, 50f, crossfade)
        soundtrack.segments.forEach { segment ->
            assertTrue(segment.endSeconds <= 50f + 1e-3f, "segment ends at ${segment.endSeconds}")
            assertTrue(segment.startSeconds >= 0f)
        }
        assertEquals(50f, soundtrack.coveredSeconds, 1e-3f)
    }

    @Test
    fun `tracks that no longer fit are simply dropped`() {
        val tracks = listOf(track("a", 60f), track("b", 60f), track("c", 60f))
        val soundtrack = SoundtrackPlanner.plan(tracks, 30f, cut)
        assertEquals(1, soundtrack.segments.size)
        assertEquals(0, soundtrack.segments.single().trackIndex)
    }

    @Test
    fun `the music is silent outside its segments and audible inside`() {
        val soundtrack = SoundtrackPlanner.plan(listOf(track("a", 20f)), 60f, cut)
        val segment = soundtrack.segments.single()
        assertEquals(0f, segment.gainAt(-1f))
        assertEquals(0f, segment.gainAt(25f))
        assertTrue(segment.gainAt(10f) > 0.9f)
    }

    @Test
    fun `settings are clamped to the supported ranges`() {
        val soundtrack = SoundtrackPlanner.plan(
            listOf(track("a", 60f)),
            50f,
            SoundtrackSettings(crossfadeSeconds = 99f, fadeSeconds = 0.01f, volume = 4f),
        )
        assertEquals(1f, soundtrack.volume)
        assertTrue(soundtrack.segments.single().fadeInSeconds >= SoundtrackSettings.MIN_FADE - 1e-3f)
    }
}
