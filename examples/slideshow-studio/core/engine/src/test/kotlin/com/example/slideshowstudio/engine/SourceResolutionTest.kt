package com.example.slideshowstudio.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceResolutionTest {

    private val settings = SlideshowSettings(mode = ImagesPerSceneMode.UP_TO_FOUR, seed = 12L)

    private fun slot(rect: NormRect, fill: Float = 1f, startZoom: Float = 1.05f, endZoom: Float = 1.12f) = SlotPlan(
        photoIndex = 0,
        rect = rect,
        motion = MotionSpec(MotionKind.ZOOM_IN, startZoom, endZoom, Vec2.Zero, Vec2.Zero),
        fill = fill,
        maxZoom = MotionSpec.MAX_ZOOM,
    )

    @Test
    fun `a full screen photo needs about the output width`() {
        val width = SourceResolution.requiredWidth(
            photo = photo("p", 6000, 4000),
            slot = slot(NormRect.Full),
            canvasAspect = settings.canvasAspect,
            canvasWidthPx = 1920,
            maxWidth = 4096,
        )
        assertTrue(width in 1920..3400, "unexpected width $width")
    }

    @Test
    fun `a quarter of the screen needs far fewer pixels`() {
        val width = SourceResolution.requiredWidth(
            photo = photo("p", 6000, 4000),
            slot = slot(NormRect(0f, 0f, 0.5f, 0.5f)),
            canvasAspect = settings.canvasAspect,
            canvasWidthPx = 1920,
            maxWidth = 4096,
        )
        assertTrue(width < 1800, "quarter slot asked for $width")
    }

    @Test
    fun `a preview sized canvas needs proportionally fewer pixels`() {
        val photo = photo("p", 6000, 4000)
        val export = SourceResolution.requiredWidth(photo, slot(NormRect.Full), settings.canvasAspect, 1920, 4096)
        val preview = SourceResolution.requiredWidth(photo, slot(NormRect.Full), settings.canvasAspect, 960, 4096)
        assertTrue(preview < export, "preview=$preview export=$export")
    }

    @Test
    fun `an uncropped photo needs no extra pixels for zooming`() {
        val photo = photo("p", 6000, 4000)
        val cropped = SourceResolution.requiredWidth(photo, slot(NormRect.Full, fill = 1f), 16f / 9f, 1920, 8192)
        val whole = SourceResolution.requiredWidth(photo, slot(NormRect.Full, fill = 0f), 16f / 9f, 1920, 8192)
        assertTrue(whole < cropped, "whole=$whole cropped=$cropped")
    }

    @Test
    fun `never asks for more pixels than the photo has or than the renderer allows`() {
        val small = photo("small", 800, 600)
        assertEquals(
            800,
            SourceResolution.requiredWidth(small, slot(NormRect.Full), 16f / 9f, 1920, 4096),
        )
        assertEquals(
            2048,
            SourceResolution.requiredWidth(photo("huge", 12000, 9000), slot(NormRect.Full), 16f / 9f, 1920, 2048),
        )
    }

    @Test
    fun `every photo of a storyboard gets a decoding width`() {
        BOTH_FORMATS.forEach { format ->
            val photos = mixedPhotos(12)
            val board = StoryboardBuilder.build(photos, settings.copy(format = format))
            val widths = SourceResolution.forStoryboard(board, photos, canvasWidthPx = format.width, maxWidth = 2048)
            assertEquals(photos.indices.toSet(), widths.keys)
            widths.forEach { (index, width) ->
                assertTrue(width in 320..2048, "photo $index -> $width")
                assertTrue(width <= photos[index].widthPx)
            }
        }
    }
}
