package com.example.slideshowstudio.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoryboardBuilderTest {

    @Test
    fun `every photo is used exactly once and never twice in the same scene`() {
        for (mode in ImagesPerSceneMode.entries) {
            for (photoCount in listOf(1, 2, 3, 5, 8, 13, 40)) {
                val photos = mixedPhotos(photoCount)
                val board = StoryboardBuilder.build(photos, SlideshowSettings(mode = mode, seed = photoCount.toLong()))
                val used = board.scenes.flatMap { scene -> scene.slots.map { it.photoIndex } }
                assertEquals(photoCount, used.size, "mode=$mode photos=$photoCount")
                assertEquals(photos.indices.toSet(), used.toSet(), "mode=$mode photos=$photoCount")
                board.scenes.forEach { scene ->
                    val ids = scene.slots.map { it.photoIndex }
                    assertEquals(ids.size, ids.toSet().size, "duplicate photo inside scene ${scene.index}")
                }
            }
        }
    }

    @Test
    fun `scene photo count never exceeds the selected mode`() {
        for (mode in ImagesPerSceneMode.entries) {
            val board = StoryboardBuilder.build(mixedPhotos(30), SlideshowSettings(mode = mode, seed = 7L))
            board.scenes.forEach { scene ->
                assertTrue(scene.photoCount in 1..mode.maxImages, "mode=$mode scene=${scene.photoCount}")
            }
        }
    }

    @Test
    fun `single mode always shows exactly one photo`() {
        val board = StoryboardBuilder.build(mixedPhotos(12), SlideshowSettings(mode = ImagesPerSceneMode.SINGLE))
        assertEquals(12, board.scenes.size)
        board.scenes.forEach { assertEquals(1, it.photoCount) }
    }

    @Test
    fun `consecutive scenes vary the number of photos`() {
        for (mode in listOf(ImagesPerSceneMode.UP_TO_TWO, ImagesPerSceneMode.UP_TO_THREE, ImagesPerSceneMode.UP_TO_FOUR)) {
            val board = StoryboardBuilder.build(mixedPhotos(60), SlideshowSettings(mode = mode, seed = 99L))
            val counts = board.scenes.map { it.photoCount }
            // The very last scene may be forced to repeat the previous count by the photos left.
            counts.dropLast(1).zipWithNext().forEach { (a, b) ->
                assertTrue(a != b, "mode=$mode repeated count $a in $counts")
            }
            assertTrue(counts.toSet().size > 1, "mode=$mode never varied: $counts")
        }
    }

    @Test
    fun `the same composition is never used twice in a row`() {
        val board = StoryboardBuilder.build(mixedPhotos(80), SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 3L))
        board.scenes.map { it.layoutId }.zipWithNext().forEach { (a, b) ->
            assertTrue(a != b, "composition $a repeated")
        }
    }

    @Test
    fun `consecutive transitions never share the same family`() {
        val board = StoryboardBuilder.build(mixedPhotos(80), SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_THREE, seed = 11L))
        val kinds = board.scenes.mapNotNull { it.transitionIn?.kind }
        assertTrue(kinds.size >= 10)
        kinds.zipWithNext().forEach { (a, b) ->
            assertTrue(a.family != b.family, "$a followed by $b")
        }
        assertTrue(kinds.toSet().size >= 6, "not enough variety: ${kinds.toSet()}")
    }

    @Test
    fun `photos inside a scene never share the same movement`() {
        val board = StoryboardBuilder.build(mixedPhotos(60), SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 5L))
        board.scenes.forEach { scene ->
            val kinds = scene.slots.map { it.motion.kind }
            assertEquals(kinds.size, kinds.toSet().size, "scene ${scene.index} repeats a movement: $kinds")
        }
    }

    @Test
    fun `no photo is ever static`() {
        val board = StoryboardBuilder.build(mixedPhotos(40), SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 21L))
        board.scenes.flatMap { it.slots }.forEach { slot ->
            val motion = slot.motion
            val moves = motion.startZoom != motion.endZoom ||
                motion.startPan != motion.endPan ||
                motion.startRotationDeg != motion.endRotationDeg
            assertTrue(moves, "static photo in ${motion.kind}")
        }
    }

    @Test
    fun `building twice with the same seed gives the same storyboard`() {
        val photos = mixedPhotos(25)
        val settings = SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 1234L)
        assertEquals(StoryboardBuilder.build(photos, settings), StoryboardBuilder.build(photos, settings))
    }

    @Test
    fun `a different seed gives a different storyboard`() {
        val photos = mixedPhotos(25)
        val a = StoryboardBuilder.build(photos, SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 1L))
        val b = StoryboardBuilder.build(photos, SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 2L))
        assertTrue(a != b)
    }

    @Test
    fun `an empty selection produces an empty storyboard`() {
        val board = StoryboardBuilder.build(emptyList(), SlideshowSettings())
        assertTrue(board.isEmpty)
        assertEquals(0f, board.totalDurationSeconds)
    }

    @Test
    fun `duration follows the selected scene duration`() {
        val photos = mixedPhotos(9)
        val board = StoryboardBuilder.build(photos, SlideshowSettings(sceneDurationSeconds = 3f, mode = ImagesPerSceneMode.SINGLE))
        assertEquals(9, board.scenes.size)
        assertEquals(27f, board.totalDurationSeconds)
        assertEquals(27 * 30, board.frameCount)
    }

    @Test
    fun `slot assignment prefers slots that match the photo shape`() {
        val photos = listOf(photo("landscape", 4000, 2000), photo("portrait", 2000, 4000))
        val wide = NormRect(0f, 0f, 0.5f, 0.5f)      // 16:9 * 1 = wide slot
        val tall = NormRect(0.5f, 0f, 0.75f, 1f)     // narrow, tall slot
        val assignment = StoryboardBuilder.assignPhotosToSlots(listOf(1, 0), listOf(wide, tall), photos, 16f / 9f)
        assertEquals(listOf(0, 1), assignment)
    }

    @Test
    fun `settings are clamped to the supported range`() {
        val board = StoryboardBuilder.build(
            mixedPhotos(4),
            SlideshowSettings(sceneDurationSeconds = 12f, transitionDurationSeconds = 3f),
        )
        assertEquals(7f, board.sceneDurationSeconds)
        assertEquals(1f, board.transitionDurationSeconds)
    }

    @Test
    fun `transition never lasts longer than half a scene`() {
        val board = StoryboardBuilder.build(
            mixedPhotos(6),
            SlideshowSettings(sceneDurationSeconds = 2f, transitionDurationSeconds = 1f),
        )
        assertTrue(board.transitionDurationSeconds <= 1f)
        assertTrue(board.transitionDurationSeconds <= board.sceneDurationSeconds / 2f)
    }

    @Test
    fun `random settings never break the invariants`() {
        val random = Random(42)
        repeat(60) {
            val mode = ImagesPerSceneMode.entries[random.nextInt(4)]
            val photos = mixedPhotos(1 + random.nextInt(30))
            val board = StoryboardBuilder.build(photos, randomSettings(random, mode))
            val used = board.scenes.flatMap { scene -> scene.slots.map { it.photoIndex } }
            assertEquals(photos.indices.toSet(), used.toSet())
            board.scenes.forEach { scene ->
                assertTrue(scene.photoCount in 1..mode.maxImages)
                assertEquals(scene.slots.size, scene.slots.map { it.photoIndex }.toSet().size)
            }
        }
    }
}
