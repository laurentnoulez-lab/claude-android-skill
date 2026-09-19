package com.example.slideshowstudio.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How the video begins and ends: from black, or out of focus. */
class OpeningEndingTest {

    private fun composerFor(opening: OpeningMode, ending: EndingMode): FrameComposer {
        val photos = mixedPhotos(6)
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(
                sceneDurationSeconds = 4f,
                mode = ImagesPerSceneMode.SINGLE,
                opening = opening,
                ending = ending,
                seed = 3L,
            ),
        )
        return FrameComposer(board, photos)
    }

    @Test
    fun `opening from black starts on a black frame and clears`() {
        val composer = composerFor(OpeningMode.FROM_BLACK, EndingMode.TO_BLACK)
        assertEquals(1f, composer.compose(0f).blackout, 1e-3f)
        assertEquals(0f, composer.compose(0f).defocus, 1e-3f)
        assertEquals(0f, composer.compose(1.5f).blackout, 1e-3f)
    }

    @Test
    fun `opening from blur starts out of focus and comes sharp`() {
        val composer = composerFor(OpeningMode.FROM_BLUR, EndingMode.TO_BLACK)
        val first = composer.compose(0f)
        assertEquals(1f, first.defocus, 1e-3f)
        assertEquals(0f, first.blackout, 1e-3f, "a blurred opening must not flash through black")
        assertTrue(first.commands.isNotEmpty(), "the first scene should already be composed")
        assertEquals(0f, composer.compose(1.5f).defocus, 1e-3f)
    }

    @Test
    fun `ending to black finishes on a black frame`() {
        val composer = composerFor(OpeningMode.FROM_BLACK, EndingMode.TO_BLACK)
        val last = composer.compose(composer.totalDurationSeconds)
        assertEquals(1f, last.blackout, 1e-3f)
        assertEquals(0f, last.defocus, 1e-3f)
    }

    @Test
    fun `ending to blur finishes out of focus without going black`() {
        val composer = composerFor(OpeningMode.FROM_BLACK, EndingMode.TO_BLUR)
        val last = composer.compose(composer.totalDurationSeconds)
        assertEquals(1f, last.defocus, 1e-3f)
        assertEquals(0f, last.blackout, 1e-3f)
        assertTrue(last.commands.isNotEmpty(), "the last scene should still be composed")
    }

    @Test
    fun `the two ends are independent`() {
        val composer = composerFor(OpeningMode.FROM_BLUR, EndingMode.TO_BLACK)
        assertEquals(1f, composer.compose(0f).defocus, 1e-3f)
        assertEquals(1f, composer.compose(composer.totalDurationSeconds).blackout, 1e-3f)
    }

    @Test
    fun `the middle of the video is neither black nor blurred`() {
        OpeningMode.entries.forEach { opening ->
            EndingMode.entries.forEach { ending ->
                val composer = composerFor(opening, ending)
                val middle = composer.compose(composer.totalDurationSeconds / 2f)
                assertEquals(0f, middle.blackout, 1e-3f, "$opening/$ending")
                assertEquals(0f, middle.defocus, 1e-3f, "$opening/$ending")
            }
        }
    }

    @Test
    fun `the opening and closing effects progress smoothly`() {
        OpeningMode.entries.forEach { opening ->
            EndingMode.entries.forEach { ending ->
                val composer = composerFor(opening, ending)
                var previous = composer.frameAt(0)
                for (index in 1..composer.frameCount) {
                    val frame = composer.frameAt(index)
                    assertTrue(
                        kotlin.math.abs(frame.blackout - previous.blackout) < 0.2f,
                        "blackout jumped at ${frame.timeSeconds} ($opening/$ending)",
                    )
                    assertTrue(
                        kotlin.math.abs(frame.defocus - previous.defocus) < 0.2f,
                        "focus jumped at ${frame.timeSeconds} ($opening/$ending)",
                    )
                    previous = frame
                }
            }
        }
    }

    @Test
    fun `the opening lasts about a second`() {
        val settings = SlideshowSettings(sceneDurationSeconds = 5f, transitionDurationSeconds = 0.6f)
        assertEquals(1f, settings.openingSeconds, 1e-3f)
        // Very short scenes shrink it rather than swallowing a whole scene.
        assertEquals(1f, settings.copy(sceneDurationSeconds = 2f).openingSeconds, 1e-3f)
    }
}
