package com.example.slideshowstudio.engine

import kotlin.random.Random

internal fun photo(id: String, width: Int, height: Int): PhotoRef = PhotoRef(id, width, height)

internal fun photo(id: String, width: Int, height: Int, focus: FocusArea): PhotoRef =
    PhotoRef(id, width, height, focus)

/** A mix of landscape, portrait, square and odd resolutions, like a real gallery selection. */
internal fun mixedPhotos(count: Int): List<PhotoRef> {
    val sizes = listOf(
        4032 to 3024,   // landscape 4:3
        3024 to 4032,   // portrait 3:4
        1080 to 1080,   // square
        1920 to 1080,   // landscape 16:9
        1080 to 1920,   // portrait 9:16
        2560 to 1600,   // wide
        1200 to 1600,   // portrait 3:4
        640 to 480,     // small landscape
    )
    return (0 until count).map { index ->
        val (w, h) = sizes[index % sizes.size]
        photo("photo-$index", w, h)
    }
}

internal fun randomSettings(random: Random, mode: ImagesPerSceneMode): SlideshowSettings = SlideshowSettings(
    sceneDurationSeconds = 2f + random.nextFloat() * 5f,
    transitionDurationSeconds = 0.5f + random.nextFloat() * 0.5f,
    mode = mode,
    format = if (random.nextBoolean()) OutputFormat.LANDSCAPE_1080P else OutputFormat.PORTRAIT_1080P,
    cropMode = CropMode.entries[random.nextInt(CropMode.entries.size)],
    photoOrder = PhotoOrder.entries[random.nextInt(PhotoOrder.entries.size)],
    backgroundMode = BackgroundMode.entries[random.nextInt(BackgroundMode.entries.size)],
    seed = random.nextLong(),
)

internal val BOTH_FORMATS = listOf(OutputFormat.LANDSCAPE_1080P, OutputFormat.PORTRAIT_1080P)
