package com.example.slideshowstudio.engine

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoryboardBuilderTest {

    /**
     * The order the viewer perceives: scene by scene. Inside one scene every photo is on screen at
     * the same time, so their slot order is a matter of composition, not of sequence.
     */
    private fun perceivedOrder(board: Storyboard): List<Int> =
        board.scenes.flatMap { scene -> scene.slots.map { it.photoIndex }.sorted() }

    @Test
    fun `every photo is used exactly once and never twice in the same scene`() {
        for (mode in ImagesPerSceneMode.entries) {
            for (order in PhotoOrder.entries) {
                for (photoCount in listOf(1, 2, 3, 5, 8, 13, 40)) {
                    val photos = mixedPhotos(photoCount)
                    val board = StoryboardBuilder.build(
                        photos,
                        SlideshowSettings(mode = mode, photoOrder = order, seed = photoCount.toLong()),
                    )
                    val used = board.scenes.flatMap { scene -> scene.slots.map { it.photoIndex } }
                    assertEquals(photoCount, used.size, "mode=$mode order=$order photos=$photoCount")
                    assertEquals(photos.indices.toSet(), used.toSet(), "mode=$mode order=$order")
                    board.scenes.forEach { scene ->
                        val ids = scene.slots.map { it.photoIndex }
                        assertEquals(ids.size, ids.toSet().size, "duplicate photo inside scene ${scene.index}")
                    }
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
        BOTH_FORMATS.forEach { format ->
            val board = StoryboardBuilder.build(
                mixedPhotos(80),
                SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, format = format, seed = 3L),
            )
            board.scenes.map { it.layoutId }.zipWithNext().forEach { (a, b) ->
                assertTrue(a != b, "composition $a repeated in $format")
            }
        }
    }

    @Test
    fun `consecutive transitions never share the same family`() {
        val board = StoryboardBuilder.build(
            mixedPhotos(80),
            SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_THREE, seed = 11L),
        )
        val kinds = board.scenes.mapNotNull { it.transitionIn?.kind }
        assertTrue(kinds.size >= 10)
        kinds.zipWithNext().forEach { (a, b) -> assertTrue(a.family != b.family, "$a followed by $b") }
        assertTrue(kinds.toSet().size >= 6, "not enough variety: ${kinds.toSet()}")
    }

    @Test
    fun `photos inside a scene never share the same movement`() {
        val board = StoryboardBuilder.build(
            mixedPhotos(60),
            SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 5L),
        )
        board.scenes.forEach { scene ->
            val kinds = scene.slots.map { it.motion.kind }
            assertEquals(kinds.size, kinds.toSet().size, "scene ${scene.index} repeats a movement: $kinds")
        }
    }

    @Test
    fun `no photo is ever static`() {
        val board = StoryboardBuilder.build(
            mixedPhotos(40),
            SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 21L),
        )
        board.scenes.flatMap { it.slots }.forEach { slot ->
            val motion = slot.motion
            val moves = motion.startZoom != motion.endZoom ||
                motion.startPan != motion.endPan ||
                motion.startRotationDeg != motion.endRotationDeg
            assertTrue(moves, "static photo in ${motion.kind}")
        }
    }

    // ------------------------------------------------------------------ photo order

    @Test
    fun `strict order keeps the photos exactly where the user put them`() {
        for (mode in ImagesPerSceneMode.entries) {
            val photos = mixedPhotos(23)
            val board = StoryboardBuilder.build(
                photos,
                SlideshowSettings(mode = mode, photoOrder = PhotoOrder.STRICT, seed = 42L),
            )
            assertEquals(photos.indices.toList(), perceivedOrder(board), "mode=$mode")
        }
    }

    @Test
    fun `adaptive order never moves a photo more than two positions`() {
        for (seed in 0L..6L) {
            val photos = mixedPhotos(40)
            val board = StoryboardBuilder.build(
                photos,
                SlideshowSettings(
                    mode = ImagesPerSceneMode.UP_TO_FOUR,
                    photoOrder = PhotoOrder.ADAPTIVE,
                    seed = seed,
                ),
            )
            perceivedOrder(board).forEachIndexed { position, photoIndex ->
                assertTrue(
                    abs(position - photoIndex) <= StoryboardBuilder.MAX_ADAPTIVE_SHIFT,
                    "photo $photoIndex landed at $position (seed=$seed)",
                )
            }
        }
    }

    @Test
    fun `adaptive order is not a shuffle in disguise`() {
        val photos = mixedPhotos(40)
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, photoOrder = PhotoOrder.ADAPTIVE, seed = 4L),
        )
        val order = perceivedOrder(board)
        val moved = order.filterIndexed { position, photoIndex -> position != photoIndex }
        // Swaps stay local and in the minority, so the user still recognises their own sequence:
        // the shift of every photo is checked separately and never exceeds two positions.
        assertTrue(moved.size < order.size / 2, "${moved.size} of ${order.size} photos moved")
    }

    @Test
    fun `shuffled order really reorders the photos`() {
        val photos = mixedPhotos(40)
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, photoOrder = PhotoOrder.SHUFFLE, seed = 8L),
        )
        val order = perceivedOrder(board)
        assertEquals(photos.indices.toSet(), order.toSet())
        val moved = order.filterIndexed { position, photoIndex -> position != photoIndex }
        assertTrue(moved.size > order.size / 2, "shuffle barely moved anything")
    }

    // ------------------------------------------------------------------ background

    @Test
    fun `a solid background uses the chosen colour everywhere`() {
        val chosen = 0xFF334455.toInt()
        val board = StoryboardBuilder.build(
            mixedPhotos(20),
            SlideshowSettings(backgroundMode = BackgroundMode.SOLID, backgroundColor = chosen, seed = 2L),
        )
        board.scenes.forEach { scene ->
            assertEquals(chosen, scene.background.color)
            assertEquals(null, scene.background.photoIndex)
            assertEquals(0f, scene.background.dim)
        }
    }

    @Test
    fun `a random background changes from scene to scene`() {
        val board = StoryboardBuilder.build(
            mixedPhotos(30),
            SlideshowSettings(backgroundMode = BackgroundMode.RANDOM, seed = 6L),
        )
        val colors = board.scenes.map { it.background.color }
        assertTrue(colors.toSet().size >= colors.size - 1, "colours repeat: $colors")
        colors.zipWithNext().forEach { (a, b) -> assertTrue(a != b) }
        board.scenes.forEach { assertEquals(null, it.background.photoIndex) }
    }

    @Test
    fun `a blurred background always comes from a photo of its own scene`() {
        val board = StoryboardBuilder.build(
            mixedPhotos(40),
            SlideshowSettings(
                mode = ImagesPerSceneMode.UP_TO_FOUR,
                backgroundMode = BackgroundMode.BLURRED_PHOTO,
                seed = 9L,
            ),
        )
        board.scenes.forEach { scene ->
            val backdrop = scene.background.photoIndex
            assertTrue(backdrop != null, "scene ${scene.index} has no backdrop")
            assertTrue(
                backdrop in scene.slots.map { it.photoIndex },
                "scene ${scene.index} uses a photo it does not show",
            )
            assertTrue(scene.background.dim > 0f, "backdrop is not dimmed")
            assertTrue(scene.background.motion != null, "backdrop does not move")
        }
    }

    @Test
    fun `the blurred background is not always the first photo of the scene`() {
        val board = StoryboardBuilder.build(
            mixedPhotos(60),
            SlideshowSettings(
                mode = ImagesPerSceneMode.UP_TO_FOUR,
                backgroundMode = BackgroundMode.BLURRED_PHOTO,
                seed = 13L,
            ),
        )
        val multi = board.scenes.filter { it.photoCount > 1 }
        assertTrue(multi.size >= 5)
        val elsewhere = multi.count { it.background.photoIndex != it.slots.first().photoIndex }
        assertTrue(elsewhere > 0, "the backdrop was always the first slot")
    }

    // ------------------------------------------------------------------ format

    @Test
    fun `the portrait format uses portrait compositions`() {
        val board = StoryboardBuilder.build(
            mixedPhotos(40),
            SlideshowSettings(
                mode = ImagesPerSceneMode.UP_TO_FOUR,
                format = OutputFormat.PORTRAIT_1080P,
                seed = 17L,
            ),
        )
        assertEquals(9f / 16f, board.canvasAspect, 1e-4f)
        board.scenes.filter { it.photoCount > 1 }.forEach { scene ->
            assertTrue(scene.layoutId.startsWith("p"), "landscape composition ${scene.layoutId} in portrait")
        }
    }

    @Test
    fun `portrait leans on vertical movement without excluding the rest`() {
        val board = StoryboardBuilder.build(
            mixedPhotos(60),
            SlideshowSettings(format = OutputFormat.PORTRAIT_1080P, mode = ImagesPerSceneMode.SINGLE, seed = 5L),
        )
        val kinds = board.scenes.flatMap { scene -> scene.slots.map { it.motion.kind } }
        val vertical = kinds.count { it == MotionKind.PAN_UP || it == MotionKind.PAN_DOWN }
        val horizontal = kinds.count { it == MotionKind.PAN_LEFT || it == MotionKind.PAN_RIGHT }
        assertTrue(vertical > horizontal, "vertical=$vertical horizontal=$horizontal")
        assertTrue(horizontal > 0, "horizontal movement disappeared entirely")
    }

    // ------------------------------------------------------------------ determinism and limits

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
        val board = StoryboardBuilder.build(
            mixedPhotos(9),
            SlideshowSettings(sceneDurationSeconds = 3f, mode = ImagesPerSceneMode.SINGLE),
        )
        assertEquals(9, board.scenes.size)
        assertEquals(27f, board.totalDurationSeconds)
        assertEquals(27 * 30, board.frameCount)
    }

    @Test
    fun `slot assignment prefers slots that match the photo shape`() {
        val photos = listOf(photo("landscape", 4000, 2000), photo("portrait", 2000, 4000))
        val wide = NormRect(0f, 0f, 0.5f, 0.5f)
        val tall = NormRect(0.5f, 0f, 0.75f, 1f)
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
        repeat(80) {
            val mode = ImagesPerSceneMode.entries[random.nextInt(4)]
            val photos = mixedPhotos(1 + random.nextInt(30))
            val settings = randomSettings(random, mode)
            val board = StoryboardBuilder.build(photos, settings)
            val used = board.scenes.flatMap { scene -> scene.slots.map { it.photoIndex } }
            assertEquals(photos.indices.toSet(), used.toSet())
            board.scenes.forEach { scene ->
                assertTrue(scene.photoCount in 1..mode.maxImages)
                assertEquals(scene.slots.size, scene.slots.map { it.photoIndex }.toSet().size)
                scene.slots.forEach { slot ->
                    assertTrue(slot.fill in 0f..1f, "fill ${slot.fill}")
                    assertTrue(slot.maxZoom >= 1f, "maxZoom ${slot.maxZoom}")
                }
            }
            if (settings.photoOrder == PhotoOrder.STRICT) {
                assertEquals(photos.indices.toList(), perceivedOrder(board))
            }
        }
    }
}
