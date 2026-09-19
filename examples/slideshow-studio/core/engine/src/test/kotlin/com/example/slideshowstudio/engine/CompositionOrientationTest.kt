package com.example.slideshowstudio.engine

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A photo must not be squeezed into a sliver just because its shape disagrees with the video's.
 * In a portrait video a landscape photo wants a full width band, and in a landscape video a
 * portrait photo wants a full height column.
 */
class CompositionOrientationTest {

    private fun scenesFor(
        photos: List<PhotoRef>,
        format: OutputFormat,
        mode: ImagesPerSceneMode,
        seed: Long,
    ): List<Scene> = StoryboardBuilder.build(
        photos,
        SlideshowSettings(mode = mode, format = format, photoOrder = PhotoOrder.STRICT, seed = seed),
    ).scenes

    private fun landscapePhotos(count: Int) =
        (0 until count).map { photo("landscape-$it", 4000, 2250) }

    private fun portraitPhotos(count: Int) =
        (0 until count).map { photo("portrait-$it", 2250, 4000) }

    @Test
    fun `a portrait video stacks landscape photos instead of shrinking them side by side`() {
        for (seed in 0L..9L) {
            val scenes = scenesFor(
                landscapePhotos(8),
                OutputFormat.PORTRAIT_1080P,
                ImagesPerSceneMode.UP_TO_TWO,
                seed,
            )
            scenes.filter { it.photoCount == 2 }.forEach { scene ->
                scene.slots.forEach { slot ->
                    // What matters is that the photo keeps the full width of the frame: a band, not
                    // a column squeezed against another photo.
                    assertTrue(
                        slot.rect.width >= 0.9f,
                        "seed=$seed ${scene.layoutId}: a landscape photo was squeezed to ${slot.rect.width} wide",
                    )
                }
            }
        }
    }

    @Test
    fun `a landscape video puts portrait photos side by side instead of in flat bands`() {
        for (seed in 0L..9L) {
            val scenes = scenesFor(
                portraitPhotos(8),
                OutputFormat.LANDSCAPE_1080P,
                ImagesPerSceneMode.UP_TO_TWO,
                seed,
            )
            scenes.filter { it.photoCount == 2 }.forEach { scene ->
                scene.slots.forEach { slot ->
                    // Full height columns, not flat bands stacked on top of each other.
                    assertTrue(
                        slot.rect.height >= 0.9f,
                        "seed=$seed ${scene.layoutId}: a portrait photo was flattened to ${slot.rect.height} tall",
                    )
                }
            }
        }
    }

    @Test
    fun `three landscape photos in a portrait video get full width bands`() {
        for (seed in 0L..5L) {
            val scenes = scenesFor(
                landscapePhotos(9),
                OutputFormat.PORTRAIT_1080P,
                ImagesPerSceneMode.UP_TO_THREE,
                seed,
            )
            scenes.filter { it.photoCount == 3 }.forEach { scene ->
                val narrow = scene.slots.count { it.rect.width < 0.9f }
                assertTrue(narrow == 0, "seed=$seed ${scene.layoutId}: $narrow slot(s) are too narrow")
            }
        }
    }

    @Test
    fun `photos keep a large share of the frame whatever their orientation`() {
        // Every photo should still be shown big: the smallest slot of a scene must not collapse.
        BOTH_FORMATS.forEach { format ->
            listOf(landscapePhotos(12), portraitPhotos(12), mixedPhotos(12)).forEach { photos ->
                val scenes = scenesFor(photos, format, ImagesPerSceneMode.UP_TO_THREE, 4L)
                scenes.forEach { scene ->
                    val smallest = scene.slots.minOf { it.rect.width * it.rect.height }
                    val expected = 0.45f / scene.photoCount
                    assertTrue(
                        smallest >= expected,
                        "$format ${scene.layoutId}: smallest slot covers $smallest of the frame",
                    )
                }
            }
        }
    }

    @Test
    fun `compositions still vary when the photos allow it`() {
        val scenes = scenesFor(mixedPhotos(40), OutputFormat.LANDSCAPE_1080P, ImagesPerSceneMode.UP_TO_FOUR, 6L)
        val layouts = scenes.map { it.layoutId }
        assertTrue(layouts.toSet().size >= 5, "only ${layouts.toSet().size} distinct compositions")
        layouts.zipWithNext().forEach { (a, b) -> assertTrue(a != b, "$a repeated") }
    }
}
