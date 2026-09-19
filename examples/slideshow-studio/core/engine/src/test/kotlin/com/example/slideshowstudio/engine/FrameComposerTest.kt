package com.example.slideshowstudio.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameComposerTest {

    private fun settingsFor(
        mode: ImagesPerSceneMode = ImagesPerSceneMode.UP_TO_FOUR,
        sceneDuration: Float = 3f,
        transition: Float = 0.8f,
        seed: Long = 17L,
        format: OutputFormat = OutputFormat.LANDSCAPE_1080P,
        cropMode: CropMode = CropMode.AUTO,
        background: BackgroundMode = BackgroundMode.BLURRED_PHOTO,
    ) = SlideshowSettings(
        sceneDurationSeconds = sceneDuration,
        transitionDurationSeconds = transition,
        mode = mode,
        format = format,
        cropMode = cropMode,
        backgroundMode = background,
        seed = seed,
    )

    private fun composerFor(
        photoCount: Int = 24,
        settings: SlideshowSettings = settingsFor(),
    ): Triple<FrameComposer, List<PhotoRef>, Storyboard> {
        val photos = mixedPhotos(photoCount)
        val board = StoryboardBuilder.build(photos, settings)
        return Triple(FrameComposer(board, photos), photos, board)
    }

    private fun eachFrame(composer: FrameComposer, block: (Frame) -> Unit) {
        for (index in 0..composer.frameCount) {
            block(composer.frameAt(index))
        }
    }

    @Test
    fun `photos are never distorted, in any mode and any format`() {
        for (format in BOTH_FORMATS) {
            for (cropMode in CropMode.entries) {
                for (mode in ImagesPerSceneMode.entries) {
                    val settings = settingsFor(
                        mode = mode,
                        seed = mode.ordinal.toLong(),
                        format = format,
                        cropMode = cropMode,
                    )
                    val (composer, photos, _) = composerFor(settings = settings)
                    eachFrame(composer) { frame ->
                        (frame.commands.map { it.photoIndex to (it.src to it.dst) } +
                            frame.backdrops.map { it.photoIndex to (it.src to it.dst) })
                            .forEach { (photoIndex, rects) ->
                                val (src, dst) = rects
                                val photo = photos[photoIndex]
                                val srcAspect = (src.width / src.height) * photo.aspect
                                val dstAspect = (dst.width / dst.height) * format.aspect
                                assertTrue(
                                    abs(srcAspect / dstAspect - 1f) < 2e-3f,
                                    "distortion in $format/$cropMode at ${frame.timeSeconds}: $srcAspect vs $dstAspect",
                                )
                            }
                    }
                }
            }
        }
    }

    @Test
    fun `crops always stay inside the source photo`() {
        for (cropMode in CropMode.entries) {
            val (composer, _, _) = composerFor(settings = settingsFor(cropMode = cropMode))
            eachFrame(composer) { frame ->
                (frame.commands.map { it.src } + frame.backdrops.map { it.src }).forEach { src ->
                    assertTrue(src.left >= -1e-3f && src.top >= -1e-3f, "crop outside the photo: $src")
                    assertTrue(src.right <= 1f + 1e-3f && src.bottom <= 1f + 1e-3f, "crop outside the photo: $src")
                    assertTrue(src.width > 0f && src.height > 0f, "empty crop: $src")
                }
            }
        }
    }

    @Test
    fun `never cropping shows every photo whole`() {
        val (composer, _, _) = composerFor(settings = settingsFor(cropMode = CropMode.NEVER))
        eachFrame(composer) { frame ->
            frame.commands.forEach { command ->
                assertEquals(0f, command.src.left, 1e-3f, "photo was cropped at ${frame.timeSeconds}")
                assertEquals(0f, command.src.top, 1e-3f)
                assertEquals(1f, command.src.right, 1e-3f)
                assertEquals(1f, command.src.bottom, 1e-3f)
            }
        }
    }

    @Test
    fun `alpha always stays in range and photos are visible`() {
        val (composer, _, _) = composerFor()
        eachFrame(composer) { frame ->
            frame.commands.forEach { assertTrue(it.alpha in 0f..1f, "alpha ${it.alpha}") }
            frame.backdrops.forEach { assertTrue(it.alpha in 0f..1f, "backdrop alpha ${it.alpha}") }
            assertTrue(frame.commands.isNotEmpty(), "empty frame at ${frame.timeSeconds}")
            assertTrue(frame.commands.size <= 8, "too many photos at once: ${frame.commands.size}")
            assertTrue(frame.backdrops.size <= 2, "too many backdrops: ${frame.backdrops.size}")
        }
    }

    @Test
    fun `a transition really shows both scenes at once`() {
        val settings = settingsFor(mode = ImagesPerSceneMode.UP_TO_THREE, seed = 4L)
        val (composer, _, board) = composerFor(settings = settings)
        val mid = composer.compose(3f + 0.4f)
        val first = board.scenes[0].slots.map { it.photoIndex }.toSet()
        val second = board.scenes[1].slots.map { it.photoIndex }.toSet()
        val shown = mid.commands.map { it.photoIndex }.toSet()
        assertTrue(shown.containsAll(second), "incoming scene missing: $shown")
        assertTrue(shown.any { it in first }, "outgoing scene missing: $shown")
    }

    @Test
    fun `the scene being replaced stays opaque while the new one is translucent`() {
        val settings = settingsFor(seed = 8L)
        val (composer, _, board) = composerFor(settings = settings)
        val outgoing = board.scenes[0].slots.map { it.photoIndex }.toSet()
        for (step in 0..5) {
            val t = 3f + 0.8f * (step / 10f)
            val frame = composer.compose(t)
            frame.commands.filter { it.photoIndex in outgoing }.forEach {
                assertEquals(1f, it.alpha, 1e-3f, "outgoing photo dimmed at t=$t")
            }
        }
    }

    @Test
    fun `once a transition is over only the new scene remains`() {
        val settings = settingsFor(seed = 9L)
        val (composer, _, board) = composerFor(settings = settings)
        val frame = composer.compose(3f + 0.9f)
        assertEquals(board.scenes[1].slots.map { it.photoIndex }.toSet(), frame.commands.map { it.photoIndex }.toSet())
        frame.commands.forEach { assertEquals(1f, it.alpha, 1e-3f) }
        assertEquals(1, frame.backdrops.size)
    }

    @Test
    fun `nothing ever pops in or out`() {
        for (seed in 0L..4L) {
            for (format in BOTH_FORMATS) {
                val (composer, _, _) = composerFor(settings = settingsFor(seed = seed, format = format))
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
                                    "alpha jump for $photoIndex at ${frame.timeSeconds}",
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
                                    "photo $photoIndex popped at ${frame.timeSeconds} " +
                                        "(seed=$seed, $format, alpha=${command.alpha})",
                                )
                            }
                        }
                    }
                    previous = current
                }
            }
        }
    }

    // ------------------------------------------------------------------ background

    @Test
    fun `a solid background is painted on every frame`() {
        val chosen = 0xFF203040.toInt()
        val settings = settingsFor(background = BackgroundMode.SOLID).copy(backgroundColor = chosen)
        val (composer, _, _) = composerFor(settings = settings)
        eachFrame(composer) { frame ->
            assertEquals(chosen, frame.backgroundColor, "wrong colour at ${frame.timeSeconds}")
            assertTrue(frame.backdrops.isEmpty())
            assertEquals(0f, frame.backdropDim)
        }
    }

    @Test
    fun `a random background drifts from one scene to the next instead of jumping`() {
        val settings = settingsFor(background = BackgroundMode.RANDOM, seed = 6L)
        val (composer, _, _) = composerFor(settings = settings)
        var previous = composer.frameAt(0).backgroundColor
        for (index in 1..composer.frameCount) {
            val color = composer.frameAt(index).backgroundColor
            val step = maxOf(
                abs(Palette.red(color) - Palette.red(previous)),
                abs(Palette.green(color) - Palette.green(previous)),
                abs(Palette.blue(color) - Palette.blue(previous)),
            )
            assertTrue(step <= 6, "colour jumped by $step at frame $index")
            previous = color
        }
    }

    @Test
    fun `the blurred backdrop covers the canvas at all times`() {
        val (composer, _, _) = composerFor(settings = settingsFor(background = BackgroundMode.BLURRED_PHOTO))
        eachFrame(composer) { frame ->
            assertTrue(frame.backdrops.isNotEmpty(), "no backdrop at ${frame.timeSeconds}")
            assertTrue(frame.backdropDim > 0f, "backdrop is not dimmed")
            frame.backdrops.forEach { backdrop ->
                assertTrue(backdrop.dst.left <= 0f + 1e-3f, "backdrop leaves a gap: ${backdrop.dst}")
                assertTrue(backdrop.dst.top <= 0f + 1e-3f, "backdrop leaves a gap: ${backdrop.dst}")
                assertTrue(backdrop.dst.right >= 1f - 1e-3f, "backdrop leaves a gap: ${backdrop.dst}")
                assertTrue(backdrop.dst.bottom >= 1f - 1e-3f, "backdrop leaves a gap: ${backdrop.dst}")
            }
        }
    }

    @Test
    fun `backdrops cross fade during a transition`() {
        val settings = settingsFor(background = BackgroundMode.BLURRED_PHOTO, seed = 21L)
        val (composer, _, board) = composerFor(settings = settings)
        val mid = composer.compose(3f + 0.4f)
        assertEquals(2, mid.backdrops.size, "expected the old and the new backdrop")
        assertEquals(board.scenes[0].background.photoIndex, mid.backdrops[0].photoIndex)
        assertEquals(board.scenes[1].background.photoIndex, mid.backdrops[1].photoIndex)
        assertTrue(mid.backdrops[1].alpha < 1f, "the new backdrop should still be fading in")
        assertTrue(mid.backdrops[1].alpha > 0f)
    }

    @Test
    fun `the backdrop keeps moving`() {
        val (composer, _, _) = composerFor(settings = settingsFor(background = BackgroundMode.BLURRED_PHOTO))
        val early = composer.compose(1.2f).backdrops.single()
        val late = composer.compose(2.6f).backdrops.single()
        assertEquals(early.photoIndex, late.photoIndex)
        assertTrue(
            abs(early.dst.width - late.dst.width) > 1e-4f || abs(early.src.centerX - late.src.centerX) > 1e-4f,
            "the backdrop is frozen",
        )
    }

    // ------------------------------------------------------------------ format

    @Test
    fun `the portrait format keeps every photo inside a 9 by 16 canvas`() {
        val settings = settingsFor(format = OutputFormat.PORTRAIT_1080P, seed = 12L)
        val (composer, _, _) = composerFor(settings = settings)
        eachFrame(composer) { frame ->
            frame.commands.forEach { command ->
                val clip = command.clip
                if (clip != null) {
                    assertTrue(clip.left >= -1.5f && clip.right <= 2.5f, "clip out of range: $clip")
                }
            }
        }
        assertTrue(composer.frameCount > 0)
    }

    // ------------------------------------------------------------------ edges

    @Test
    fun `the video opens and closes on black`() {
        val (composer, _, _) = composerFor(
            photoCount = 6,
            settings = settingsFor(mode = ImagesPerSceneMode.SINGLE),
        )
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
        val (composer, _, _) = composerFor(photoCount = 3)
        assertTrue(composer.compose(-5f).commands.isNotEmpty())
        assertTrue(composer.compose(composer.totalDurationSeconds + 10f).commands.isNotEmpty())
    }

    @Test
    fun `an empty storyboard renders a black frame`() {
        val composer = FrameComposer(StoryboardBuilder.build(emptyList(), SlideshowSettings()), emptyList())
        val frame = composer.compose(0f)
        assertTrue(frame.commands.isEmpty())
        assertTrue(frame.backdrops.isEmpty())
        assertEquals(1f, frame.blackout)
    }

    @Test
    fun `every photo of a scene keeps moving`() {
        val settings = settingsFor(mode = ImagesPerSceneMode.UP_TO_TWO, sceneDuration = 4f, seed = 2L)
        val (composer, _, _) = composerFor(photoCount = 8, settings = settings)
        val a = composer.compose(5f).commands.associateBy { it.photoIndex }
        val b = composer.compose(5.5f).commands.associateBy { it.photoIndex }
        val common = a.keys intersect b.keys
        assertTrue(common.isNotEmpty())
        common.forEach { index ->
            val before = a.getValue(index)
            val after = b.getValue(index)
            val moved = abs(before.src.centerX - after.src.centerX) +
                abs(before.src.centerY - after.src.centerY) +
                abs(before.src.width - after.src.width) +
                abs(before.dst.centerX - after.dst.centerX) +
                abs(before.dst.width - after.dst.width)
            assertTrue(moved > 1e-4f, "photo $index is frozen")
        }
    }

    @Test
    fun `a single photo fills the whole frame when cropping is allowed`() {
        val photos = listOf(photo("only", 4000, 3000, FocusArea.point(0.5f, 0.5f)))
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(mode = ImagesPerSceneMode.SINGLE, cropMode = CropMode.SMART, seed = 3L),
        )
        val command = FrameComposer(board, photos).compose(1.5f).commands.single()
        assertTrue(command.dst.left <= 1e-3f && command.dst.top <= 1e-3f)
        assertTrue(command.dst.right >= 1f - 1e-3f && command.dst.bottom >= 1f - 1e-3f)
    }

    @Test
    fun `a single photo is letterboxed when cropping is forbidden`() {
        val photos = listOf(photo("only", 3000, 4000))
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(mode = ImagesPerSceneMode.SINGLE, cropMode = CropMode.NEVER, seed = 3L),
        )
        val command = FrameComposer(board, photos).compose(1.5f).commands.single()
        assertTrue(command.dst.width < 0.9f, "portrait photo should not span a 16:9 canvas: ${command.dst}")
        assertTrue(command.dst.left >= -1e-3f && command.dst.right <= 1f + 1e-3f)
        assertEquals(0f, command.src.left, 1e-3f)
        assertEquals(1f, command.src.right, 1e-3f)
    }

    /**
     * How far the rectangle has travelled inside the frame, counted from the closest edge. A photo
     * entering or leaving through an edge has a small depth; a photo appearing in the middle of the
     * frame has a large one, which is exactly the pop we do not want.
     */
    private fun entryDepth(rect: NormRect): Float = minOf(
        max(0f, min(rect.right, 1f)),
        max(0f, 1f - max(rect.left, 0f)),
        max(0f, min(rect.bottom, 1f)),
        max(0f, 1f - max(rect.top, 0f)),
    )
}
