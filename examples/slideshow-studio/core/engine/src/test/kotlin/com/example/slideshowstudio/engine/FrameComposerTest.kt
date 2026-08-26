package com.example.slideshowstudio.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameComposerTest {

    private fun composer(
        photoCount: Int = 24,
        mode: ImagesPerSceneMode = ImagesPerSceneMode.UP_TO_FOUR,
        sceneDuration: Float = 3f,
        transition: Float = 0.8f,
        seed: Long = 17L,
    ): Pair<FrameComposer, List<PhotoRef>> {
        val photos = mixedPhotos(photoCount)
        val settings = SlideshowSettings(
            sceneDurationSeconds = sceneDuration,
            transitionDurationSeconds = transition,
            mode = mode,
            seed = seed,
        )
        val board = StoryboardBuilder.build(photos, settings)
        return FrameComposer(board, photos) to photos
    }

    private fun eachFrame(
        composer: FrameComposer,
        block: (Frame) -> Unit,
    ) {
        for (index in 0..composer.frameCount) {
            block(composer.frameAt(index))
        }
    }

    @Test
    fun `photos are never distorted`() {
        for (mode in ImagesPerSceneMode.entries) {
            val (composer, photos) = composer(mode = mode, seed = mode.ordinal.toLong())
            eachFrame(composer) { frame ->
                frame.commands.forEach { command ->
                    val photo = photos[command.photoIndex]
                    val srcAspect = (command.src.width / command.src.height) * photo.aspect
                    val dstAspect = (command.dst.width / command.dst.height) * (1920f / 1080f)
                    assertTrue(
                        abs(srcAspect / dstAspect - 1f) < 2e-3f,
                        "distortion at t=${frame.timeSeconds}: src=$srcAspect dst=$dstAspect",
                    )
                }
            }
        }
    }

    @Test
    fun `crops always stay inside the source photo`() {
        val (composer, _) = composer()
        eachFrame(composer) { frame ->
            frame.commands.forEach { command ->
                val src = command.src
                assertTrue(src.left >= -1e-3f && src.top >= -1e-3f, "crop outside the photo: $src")
                assertTrue(src.right <= 1f + 1e-3f && src.bottom <= 1f + 1e-3f, "crop outside the photo: $src")
                assertTrue(src.width > 0f && src.height > 0f, "empty crop: $src")
            }
        }
    }

    @Test
    fun `alpha always stays in range and photos are visible`() {
        val (composer, _) = composer()
        eachFrame(composer) { frame ->
            frame.commands.forEach { command ->
                assertTrue(command.alpha in 0f..1f, "alpha ${command.alpha}")
            }
            assertTrue(frame.commands.isNotEmpty(), "empty frame at ${frame.timeSeconds}")
            assertTrue(frame.commands.size <= 8, "too many photos at once: ${frame.commands.size}")
        }
    }

    @Test
    fun `a transition really shows both scenes at once`() {
        val (composer, _) = composer(mode = ImagesPerSceneMode.UP_TO_THREE, seed = 4L)
        val board = StoryboardBuilder.build(
            mixedPhotos(24),
            SlideshowSettings(sceneDurationSeconds = 3f, transitionDurationSeconds = 0.8f, mode = ImagesPerSceneMode.UP_TO_THREE, seed = 4L),
        )
        val sceneStart = 3f // start of scene 1
        val mid = composer.compose(sceneStart + 0.4f)
        val first = board.scenes[0].slots.map { it.photoIndex }.toSet()
        val second = board.scenes[1].slots.map { it.photoIndex }.toSet()
        val shown = mid.commands.map { it.photoIndex }.toSet()
        assertTrue(shown.containsAll(second), "incoming scene missing: $shown")
        assertTrue(shown.any { it in first }, "outgoing scene missing: $shown")
    }

    @Test
    fun `the scene being replaced stays opaque while the new one is translucent`() {
        val (composer, _) = composer(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 8L)
        val board = StoryboardBuilder.build(
            mixedPhotos(24),
            SlideshowSettings(sceneDurationSeconds = 3f, transitionDurationSeconds = 0.8f, mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 8L),
        )
        val outgoing = board.scenes[0].slots.map { it.photoIndex }.toSet()
        for (step in 0..5) {
            val t = 3f + 0.8f * (step / 10f) // first half of the transition
            val frame = composer.compose(t)
            frame.commands.filter { it.photoIndex in outgoing }.forEach {
                assertEquals(1f, it.alpha, 1e-3f, "outgoing photo dimmed at t=$t")
            }
        }
    }

    @Test
    fun `once a transition is over only the new scene remains`() {
        val photos = mixedPhotos(24)
        val settings = SlideshowSettings(sceneDurationSeconds = 3f, transitionDurationSeconds = 0.8f, mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 9L)
        val board = StoryboardBuilder.build(photos, settings)
        val composer = FrameComposer(board, photos)
        val frame = composer.compose(3f + 0.9f)
        val expected = board.scenes[1].slots.map { it.photoIndex }.toSet()
        assertEquals(expected, frame.commands.map { it.photoIndex }.toSet())
        frame.commands.forEach { assertEquals(1f, it.alpha, 1e-3f) }
    }

    @Test
    fun `nothing ever pops in or out`() {
        for (seed in 0L..4L) {
            val (composer, _) = composer(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = seed)
            var previous = composer.frameAt(0).commands.associateBy { it.photoIndex }
            for (index in 1..composer.frameCount) {
                val frame = composer.frameAt(index)
                val current = frame.commands.associateBy { it.photoIndex }
                (previous.keys + current.keys).forEach { photoIndex ->
                    val before = previous[photoIndex]
                    val after = current[photoIndex]
                    when {
                        before != null && after != null -> {
                            assertTrue(
                                abs(before.alpha - after.alpha) < 0.25f,
                                "alpha jump for $photoIndex at ${frame.timeSeconds}: ${before.alpha} -> ${after.alpha}",
                            )
                            val move = max(
                                abs(before.dst.centerX - after.dst.centerX),
                                abs(before.dst.centerY - after.dst.centerY),
                            )
                            assertTrue(move < 0.15f, "jump for $photoIndex at ${frame.timeSeconds}: $move")
                        }
                        // Appearing or disappearing is only allowed while transparent, or while
                        // crossing an edge of the frame (a photo sliding in or out).
                        else -> {
                            val command = before ?: after!!
                            val hidden = command.alpha < 0.06f || entryDepth(command.dst) < 0.15f
                            assertTrue(
                                hidden,
                                "photo $photoIndex popped at ${frame.timeSeconds} (seed=$seed) " +
                                    "(alpha=${command.alpha}, depth=${entryDepth(command.dst)}, dst=${command.dst})",
                            )
                        }
                    }
                }
                previous = current
            }
        }
    }

    @Test
    fun `the video opens and closes on black`() {
        val (composer, _) = composer(photoCount = 6, mode = ImagesPerSceneMode.SINGLE)
        assertEquals(1f, composer.compose(0f).blackout, 1e-3f)
        assertEquals(1f, composer.compose(composer.totalDurationSeconds).blackout, 1e-3f)
        assertEquals(0f, composer.compose(composer.totalDurationSeconds / 2f).blackout, 1e-3f)
    }

    @Test
    fun `frame count matches the requested frame rate`() {
        val photos = mixedPhotos(5)
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(sceneDurationSeconds = 4f, mode = ImagesPerSceneMode.SINGLE, fps = 30),
        )
        val composer = FrameComposer(board, photos)
        assertEquals(5 * 4 * 30, composer.frameCount)
        assertEquals(20f, composer.totalDurationSeconds)
    }

    @Test
    fun `time outside the timeline is clamped instead of crashing`() {
        val (composer, _) = composer(photoCount = 3)
        assertTrue(composer.compose(-5f).commands.isNotEmpty())
        assertTrue(composer.compose(composer.totalDurationSeconds + 10f).commands.isNotEmpty())
    }

    @Test
    fun `an empty storyboard renders a black frame`() {
        val composer = FrameComposer(StoryboardBuilder.build(emptyList(), SlideshowSettings()), emptyList())
        val frame = composer.compose(0f)
        assertTrue(frame.commands.isEmpty())
        assertEquals(1f, frame.blackout)
    }

    @Test
    fun `every photo of a scene keeps moving`() {
        val (composer, _) = composer(photoCount = 8, mode = ImagesPerSceneMode.UP_TO_TWO, sceneDuration = 4f, seed = 2L)
        val a = composer.compose(5f).commands.associateBy { it.photoIndex }
        val b = composer.compose(5.5f).commands.associateBy { it.photoIndex }
        val common = a.keys intersect b.keys
        assertTrue(common.isNotEmpty())
        common.forEach { index ->
            val before = a.getValue(index).src
            val after = b.getValue(index).src
            val moved = abs(before.centerX - after.centerX) + abs(before.centerY - after.centerY) +
                abs(before.width - after.width)
            assertTrue(moved > 1e-4f, "photo $index is frozen")
        }
    }

    @Test
    fun `a single photo fills the whole frame`() {
        val photos = listOf(photo("only", 4000, 3000))
        val board = StoryboardBuilder.build(photos, SlideshowSettings(mode = ImagesPerSceneMode.SINGLE, seed = 3L))
        val composer = FrameComposer(board, photos)
        val command = composer.compose(1.5f).commands.single()
        assertTrue(command.dst.left <= 0f + 1e-3f && command.dst.top <= 0f + 1e-3f)
        assertTrue(command.dst.right >= 1f - 1e-3f && command.dst.bottom >= 1f - 1e-3f)
    }

    /**
     * How far the rectangle has travelled inside the frame, counted from the closest edge. A photo
     * entering or leaving through an edge has a small depth; a photo appearing in the middle of the
     * frame has a large one, which is exactly the pop we do not want.
     */
    private fun entryDepth(rect: NormRect): Float = minOf(
        max(0f, min(rect.right, 1f)),          // entering from the left
        max(0f, 1f - max(rect.left, 0f)),      // entering from the right
        max(0f, min(rect.bottom, 1f)),         // entering from the top
        max(0f, 1f - max(rect.top, 0f)),       // entering from the bottom
    )
}
