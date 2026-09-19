package com.example.slideshowstudio.engine

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Photos the user marked as important: alone in their scene, held longer, animated more calmly. */
class HighlightTest {

    private fun photosWithImportant(count: Int, importantEvery: Int): List<PhotoRef> =
        mixedPhotos(count).mapIndexed { index, photo ->
            photo.copy(isImportant = index % importantEvery == 0)
        }

    @Test
    fun `an important photo is always alone, whatever the mode allows`() {
        for (mode in ImagesPerSceneMode.entries) {
            for (order in PhotoOrder.entries) {
                val photos = photosWithImportant(30, importantEvery = 4)
                val board = StoryboardBuilder.build(
                    photos,
                    SlideshowSettings(mode = mode, photoOrder = order, seed = mode.ordinal.toLong()),
                )
                board.scenes.forEach { scene ->
                    val important = scene.slots.filter { photos[it.photoIndex].isImportant }
                    if (important.isNotEmpty()) {
                        assertEquals(
                            1,
                            scene.photoCount,
                            "mode=$mode order=$order: scene ${scene.index} shares an important photo",
                        )
                        assertTrue(scene.isHighlight, "scene ${scene.index} is not marked as a highlight")
                    }
                }
            }
        }
    }

    @Test
    fun `every important photo still appears exactly once`() {
        for (order in PhotoOrder.entries) {
            val photos = photosWithImportant(25, importantEvery = 3)
            val board = StoryboardBuilder.build(
                photos,
                SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, photoOrder = order, seed = 5L),
            )
            val used = board.scenes.flatMap { scene -> scene.slots.map { it.photoIndex } }
            assertEquals(photos.indices.toSet(), used.toSet(), "order=$order")
            assertEquals(photos.size, used.size, "order=$order")
        }
    }

    @Test
    fun `marking every photo as important gives one scene per photo`() {
        val photos = mixedPhotos(12).map { it.copy(isImportant = true) }
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 2L),
        )
        assertEquals(12, board.scenes.size)
        board.scenes.forEach { assertEquals(1, it.photoCount) }
    }

    @Test
    fun `other photos are still grouped around the important ones`() {
        val photos = photosWithImportant(40, importantEvery = 5)
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 7L),
        )
        assertTrue(board.scenes.any { it.photoCount > 1 }, "everything ended up as solo scenes")
        assertTrue(board.scenes.count { it.isHighlight } == 8, "wrong number of highlights")
    }

    @Test
    fun `strict order survives important photos`() {
        val photos = photosWithImportant(23, importantEvery = 3)
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(
                mode = ImagesPerSceneMode.UP_TO_FOUR,
                photoOrder = PhotoOrder.STRICT,
                seed = 11L,
            ),
        )
        val order = board.scenes.flatMap { scene -> scene.slots.map { it.photoIndex }.sorted() }
        assertEquals(photos.indices.toList(), order)
    }

    @Test
    fun `adaptive order keeps important photos in place`() {
        val photos = photosWithImportant(30, importantEvery = 4)
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(
                mode = ImagesPerSceneMode.UP_TO_FOUR,
                photoOrder = PhotoOrder.ADAPTIVE,
                seed = 3L,
            ),
        )
        val order = board.scenes.flatMap { scene -> scene.slots.map { it.photoIndex }.sorted() }
        order.forEachIndexed { position, photoIndex ->
            assertTrue(
                abs(position - photoIndex) <= StoryboardBuilder.MAX_ADAPTIVE_SHIFT,
                "photo $photoIndex landed at $position",
            )
        }
    }

    @Test
    fun `an important photo is held longer than the others`() {
        val photos = photosWithImportant(12, importantEvery = 4)
        val settings = SlideshowSettings(
            sceneDurationSeconds = 4f,
            mode = ImagesPerSceneMode.UP_TO_THREE,
            seed = 9L,
        )
        val board = StoryboardBuilder.build(photos, settings)
        val highlights = board.scenes.filter { it.isHighlight }
        val others = board.scenes.filterNot { it.isHighlight }
        assertTrue(highlights.isNotEmpty() && others.isNotEmpty())
        highlights.forEach { assertTrue(it.durationSeconds > 4f, "highlight lasts ${it.durationSeconds}") }
        others.forEach { assertEquals(4f, it.durationSeconds, 1e-3f) }
        assertEquals(
            board.scenes.sumOf { it.durationSeconds.toDouble() }.toFloat(),
            board.totalDurationSeconds,
            1e-2f,
        )
    }

    @Test
    fun `the timeline finds the right scene when durations differ`() {
        val photos = photosWithImportant(9, importantEvery = 3)
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(sceneDurationSeconds = 3f, mode = ImagesPerSceneMode.SINGLE, seed = 1L),
        )
        board.scenes.forEachIndexed { index, scene ->
            val start = board.sceneStartTimes[index]
            assertEquals(index, board.sceneIndexAt(start + 0.01f), "start of scene $index")
            assertEquals(index, board.sceneIndexAt(start + scene.durationSeconds - 0.01f), "end of scene $index")
        }
        assertEquals(0, board.sceneIndexAt(-5f))
        assertEquals(board.scenes.lastIndex, board.sceneIndexAt(board.totalDurationSeconds + 5f))
    }

    @Test
    fun `an important photo moves more gently than the rest`() {
        val photos = photosWithImportant(24, importantEvery = 3)
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 4L),
        )
        val highlightMotions = board.scenes.filter { it.isHighlight }.flatMap { it.slots }.map { it.motion }
        assertTrue(highlightMotions.isNotEmpty())
        highlightMotions.forEach { motion ->
            assertTrue(abs(motion.endZoom - motion.startZoom) <= 0.09f, "zoom travel ${motion.kind}")
            assertEquals(0f, motion.startRotationDeg, "a highlight should not rotate")
            assertEquals(0f, motion.endRotationDeg, "a highlight should not rotate")
            assertTrue(abs(motion.startPan.x) <= 0.25f && abs(motion.startPan.y) <= 0.25f)
        }
    }

    @Test
    fun `transitions around an important photo stay calm`() {
        val photos = photosWithImportant(30, importantEvery = 4)
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 6L),
        )
        val calm = setOf(TransitionFamily.FADE, TransitionFamily.ZOOM, TransitionFamily.ROTATE)
        board.scenes.forEachIndexed { index, scene ->
            val previous = board.scenes.getOrNull(index - 1)
            if (scene.isHighlight || previous?.isHighlight == true) {
                val kind = scene.transitionIn?.kind ?: return@forEachIndexed
                assertTrue(kind.family in calm, "scene $index arrives with $kind")
            }
        }
    }

    @Test
    fun `frames stay continuous across scenes of different lengths`() {
        val photos = photosWithImportant(16, importantEvery = 3)
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(sceneDurationSeconds = 3f, mode = ImagesPerSceneMode.UP_TO_THREE, seed = 8L),
        )
        val composer = FrameComposer(board, photos)
        var previous = composer.frameAt(0).commands.associateBy { it.photoIndex }
        for (index in 1..composer.frameCount) {
            val frame = composer.frameAt(index)
            val current = frame.commands.associateBy { it.photoIndex }
            (previous.keys intersect current.keys).forEach { photoIndex ->
                val before = previous.getValue(photoIndex)
                val after = current.getValue(photoIndex)
                assertTrue(
                    abs(before.alpha - after.alpha) < 0.25f,
                    "alpha jump for $photoIndex at ${frame.timeSeconds}",
                )
            }
            assertTrue(frame.commands.isNotEmpty(), "empty frame at ${frame.timeSeconds}")
            previous = current
        }
    }

    @Test
    fun `random settings never put an important photo in a group`() {
        val random = Random(21)
        repeat(60) {
            val mode = ImagesPerSceneMode.entries[random.nextInt(4)]
            val photos = mixedPhotos(2 + random.nextInt(28)).map {
                it.copy(isImportant = random.nextInt(3) == 0)
            }
            val board = StoryboardBuilder.build(photos, randomSettings(random, mode))
            val used = board.scenes.flatMap { scene -> scene.slots.map { it.photoIndex } }
            assertEquals(photos.indices.toSet(), used.toSet())
            board.scenes.forEach { scene ->
                if (scene.slots.any { photos[it.photoIndex].isImportant }) {
                    assertEquals(1, scene.photoCount, "an important photo was grouped")
                }
            }
        }
    }
}
