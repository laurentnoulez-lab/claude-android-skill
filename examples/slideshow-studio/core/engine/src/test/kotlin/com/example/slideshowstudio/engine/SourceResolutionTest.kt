package com.example.slideshowstudio.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceResolutionTest {

    private val settings = SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 12L)

    @Test
    fun `a full screen photo needs about the output width`() {
        val width = SourceResolution.requiredWidth(
            photo = photo("p", 6000, 4000),
            slot = NormRect.Full,
            motion = MotionSpec(MotionKind.ZOOM_IN, 1.05f, 1.12f, Vec2.Zero, Vec2.Zero),
            settings = settings,
            maxWidth = 4096,
        )
        assertTrue(width in 1920..3200, "unexpected width $width")
    }

    @Test
    fun `a quarter of the screen needs far fewer pixels`() {
        val quarter = NormRect(0f, 0f, 0.5f, 0.5f)
        val width = SourceResolution.requiredWidth(
            photo = photo("p", 6000, 4000),
            slot = quarter,
            motion = MotionSpec(MotionKind.ZOOM_IN, 1.05f, 1.10f, Vec2.Zero, Vec2.Zero),
            settings = settings,
            maxWidth = 4096,
        )
        assertTrue(width < 1800, "quarter slot asked for $width")
    }

    @Test
    fun `never asks for more pixels than the photo has or than the renderer allows`() {
        val small = photo("small", 800, 600)
        val width = SourceResolution.requiredWidth(
            photo = small,
            slot = NormRect.Full,
            motion = MotionSpec(MotionKind.ZOOM_IN, 1.2f, 1.3f, Vec2.Zero, Vec2.Zero),
            settings = settings,
            maxWidth = 4096,
        )
        assertEquals(800, width)

        val capped = SourceResolution.requiredWidth(
            photo = photo("huge", 12000, 9000),
            slot = NormRect.Full,
            motion = MotionSpec(MotionKind.ZOOM_IN, 1.2f, 1.3f, Vec2.Zero, Vec2.Zero),
            settings = settings,
            maxWidth = 2048,
        )
        assertEquals(2048, capped)
    }

    @Test
    fun `a portrait photo in a wide slot needs extra pixels`() {
        val portrait = photo("portrait", 3000, 4000)
        val wide = SourceResolution.requiredWidth(portrait, NormRect.Full, still(), settings, 8192)
        val tall = SourceResolution.requiredWidth(
            portrait,
            NormRect(0f, 0f, 0.25f, 1f),
            still(),
            settings,
            8192,
        )
        assertTrue(wide > tall, "wide=$wide tall=$tall")
    }

    @Test
    fun `every photo of a storyboard gets a decoding width`() {
        val photos = mixedPhotos(12)
        val board = StoryboardBuilder.build(photos, settings)
        val widths = SourceResolution.forStoryboard(board, photos, maxWidth = 2048)
        assertEquals(photos.indices.toSet(), widths.keys)
        widths.forEach { (index, width) ->
            assertTrue(width in 320..2048, "photo $index -> $width")
            assertTrue(width <= photos[index].widthPx)
        }
    }

    private fun still() = MotionSpec(MotionKind.ZOOM_IN, 1.05f, 1.05f, Vec2.Zero, Vec2.Zero)
}
