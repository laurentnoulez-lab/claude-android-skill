package com.example.slideshowstudio.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Photos the user tied together: always in the same scene, never split, never reused. */
class PhotoGroupTest {

    private fun grouped(count: Int, groups: Map<String, List<Int>>): List<PhotoRef> {
        val byPhoto = buildMap {
            groups.forEach { (id, members) -> members.forEach { put(it, id) } }
        }
        return mixedPhotos(count).mapIndexed { index, photo -> photo.copy(groupId = byPhoto[index]) }
    }

    private fun sceneOf(board: Storyboard, photoIndex: Int): Scene =
        board.scenes.first { scene -> scene.slots.any { it.photoIndex == photoIndex } }

    @Test
    fun `a group is always shown in a single scene`() {
        for (order in PhotoOrder.entries) {
            val photos = grouped(12, mapOf("A" to listOf(1, 2), "B" to listOf(5, 6, 7), "C" to listOf(9, 10, 11)))
            val board = StoryboardBuilder.build(
                photos,
                SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, photoOrder = order, seed = 3L),
            )
            listOf(listOf(1, 2), listOf(5, 6, 7), listOf(9, 10, 11)).forEach { members ->
                val scene = sceneOf(board, members.first())
                assertEquals(
                    members.toSet(),
                    scene.slots.map { it.photoIndex }.toSet(),
                    "order=$order: group ${members} is not alone together in scene ${scene.index}",
                )
            }
        }
    }

    @Test
    fun `grouped photos are never used anywhere else`() {
        val photos = grouped(14, mapOf("A" to listOf(2, 3), "B" to listOf(8, 9, 10)))
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 5L),
        )
        val used = board.scenes.flatMap { scene -> scene.slots.map { it.photoIndex } }
        assertEquals(photos.indices.toSet(), used.toSet())
        assertEquals(photos.size, used.size, "a photo appeared twice")
    }

    @Test
    fun `ungrouped photos are still combined freely around the groups`() {
        val photos = grouped(20, mapOf("A" to listOf(4, 5)))
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 9L),
        )
        val freeScenes = board.scenes.filterNot { scene ->
            scene.slots.map { it.photoIndex }.toSet() == setOf(4, 5)
        }
        assertTrue(freeScenes.any { it.photoCount > 1 }, "free photos were never combined")
    }

    @Test
    fun `a group keeps its place in strict order`() {
        val photos = grouped(12, mapOf("A" to listOf(2, 3), "B" to listOf(6, 7, 8)))
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(
                mode = ImagesPerSceneMode.UP_TO_FOUR,
                photoOrder = PhotoOrder.STRICT,
                seed = 7L,
            ),
        )
        val order = board.scenes.flatMap { scene -> scene.slots.map { it.photoIndex }.sorted() }
        assertEquals(photos.indices.toList(), order)
    }

    @Test
    fun `an important photo is never dragged into a group`() {
        val photos = mixedPhotos(10).mapIndexed { index, photo ->
            when (index) {
                3 -> photo.copy(groupId = "A", isImportant = true)
                4 -> photo.copy(groupId = "A")
                5 -> photo.copy(groupId = "A")
                else -> photo
            }
        }
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 11L),
        )
        assertEquals(1, sceneOf(board, 3).photoCount, "the important photo was grouped")
        assertEquals(
            setOf(4, 5),
            sceneOf(board, 4).slots.map { it.photoIndex }.toSet(),
            "the rest of the group did not stay together",
        )
    }

    @Test
    fun `a group larger than a scene can hold becomes consecutive full scenes`() {
        val photos = grouped(10, mapOf("A" to listOf(1, 2, 3, 4, 5, 6)))
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 13L),
        )
        val first = sceneOf(board, 1)
        val second = sceneOf(board, 5)
        assertEquals(setOf(1, 2, 3, 4), first.slots.map { it.photoIndex }.toSet())
        assertEquals(setOf(5, 6), second.slots.map { it.photoIndex }.toSet())
        assertEquals(second.index, first.index + 1, "the two halves are not consecutive")
    }

    @Test
    fun `a group of one is just a photo`() {
        val photos = grouped(8, mapOf("A" to listOf(3)))
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 2L),
        )
        assertEquals(photos.indices.toSet(), board.scenes.flatMap { s -> s.slots.map { it.photoIndex } }.toSet())
    }

    @Test
    fun `shuffling moves groups around without breaking them`() {
        val photos = grouped(16, mapOf("A" to listOf(1, 2), "B" to listOf(7, 8, 9)))
        val board = StoryboardBuilder.build(
            photos,
            SlideshowSettings(
                mode = ImagesPerSceneMode.UP_TO_FOUR,
                photoOrder = PhotoOrder.SHUFFLE,
                seed = 4L,
            ),
        )
        assertEquals(setOf(1, 2), sceneOf(board, 1).slots.map { it.photoIndex }.toSet())
        assertEquals(setOf(7, 8, 9), sceneOf(board, 7).slots.map { it.photoIndex }.toSet())
    }

    @Test
    fun `groups survive every combination of settings`() {
        val random = Random(31)
        repeat(60) {
            val mode = ImagesPerSceneMode.entries[random.nextInt(4)]
            val size = 8 + random.nextInt(16)
            val groupStart = random.nextInt(size - 3)
            val members = listOf(groupStart, groupStart + 1, groupStart + 2)
            val photos = mixedPhotos(size).mapIndexed { index, photo ->
                when {
                    index in members -> photo.copy(groupId = "G")
                    random.nextInt(6) == 0 -> photo.copy(isImportant = true)
                    else -> photo
                }
            }
            val board = StoryboardBuilder.build(photos, randomSettings(random, mode))
            val used = board.scenes.flatMap { scene -> scene.slots.map { it.photoIndex } }
            assertEquals(photos.indices.toSet(), used.toSet())
            assertEquals(photos.size, used.size)
            val scene = sceneOf(board, members.first())
            assertEquals(
                members.toSet(),
                scene.slots.map { it.photoIndex }.toSet(),
                "mode=$mode: the group was broken up",
            )
        }
    }
}
