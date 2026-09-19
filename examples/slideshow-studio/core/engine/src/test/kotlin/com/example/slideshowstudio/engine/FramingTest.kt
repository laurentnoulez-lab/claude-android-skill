package com.example.slideshowstudio.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FramingTest {

    private val landscape = 16f / 9f
    private val portrait = 9f / 16f

    @Test
    fun `never cropping keeps the whole photo visible`() {
        for (aspect in listOf(landscape, portrait)) {
            for (photo in mixedPhotos(8)) {
                val slot = NormRect(0f, 0f, 0.5f, 1f)
                val framing = PhotoFraming.plan(photo, slot, aspect, fill = 0f, zoom = 1f, pan = Vec2.Zero)
                assertEquals(0f, framing.src.left, 1e-4f)
                assertEquals(0f, framing.src.top, 1e-4f)
                assertEquals(1f, framing.src.right, 1e-4f)
                assertEquals(1f, framing.src.bottom, 1e-4f)
            }
        }
    }

    @Test
    fun `a full fill covers the slot exactly`() {
        val slot = NormRect(0.1f, 0.2f, 0.6f, 0.9f)
        val framing = PhotoFraming.plan(
            photo = photo("p", 4000, 3000),
            slot = slot,
            canvasAspect = landscape,
            fill = 1f,
            zoom = 1f,
            pan = Vec2.Zero,
        )
        assertEquals(slot.left, framing.dst.left, 1e-4f)
        assertEquals(slot.right, framing.dst.right, 1e-4f)
        assertEquals(slot.top, framing.dst.top, 1e-4f)
        assertEquals(slot.bottom, framing.dst.bottom, 1e-4f)
    }

    @Test
    fun `source and destination always share the same shape`() {
        val slots = listOf(
            NormRect(0f, 0f, 1f, 1f),
            NormRect(0f, 0f, 0.25f, 1f),
            NormRect(0f, 0f, 1f, 0.28f),
            NormRect(0.2f, 0.3f, 0.7f, 0.6f),
        )
        for (aspect in listOf(landscape, portrait)) {
            for (photo in mixedPhotos(8)) {
                for (slot in slots) {
                    for (fill in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                        val framing = PhotoFraming.plan(photo, slot, aspect, fill, zoom = 1.1f, pan = Vec2(0.3f, -0.2f))
                        val srcAspect = (framing.src.width / framing.src.height) * photo.aspect
                        val dstAspect = (framing.dst.width / framing.dst.height) * aspect
                        assertTrue(
                            abs(srcAspect / dstAspect - 1f) < 2e-3f,
                            "fill=$fill src=$srcAspect dst=$dstAspect",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the photo never spills out of its slot`() {
        val slot = NormRect(0.15f, 0.2f, 0.65f, 0.75f)
        for (photo in mixedPhotos(8)) {
            for (fill in listOf(0f, 0.3f, 0.6f, 1f)) {
                for (shift in listOf(Vec2(-1f, -1f), Vec2(1f, 1f), Vec2.Zero)) {
                    val framing = PhotoFraming.plan(
                        photo = photo,
                        slot = slot,
                        canvasAspect = landscape,
                        fill = fill,
                        zoom = 1f,
                        pan = Vec2.Zero,
                        displayScale = 0.9f,
                        displayShift = shift,
                    )
                    assertTrue(framing.dst.left >= slot.left - 1e-4f, "${framing.dst} vs $slot")
                    assertTrue(framing.dst.right <= slot.right + 1e-4f, "${framing.dst} vs $slot")
                    assertTrue(framing.dst.top >= slot.top - 1e-4f, "${framing.dst} vs $slot")
                    assertTrue(framing.dst.bottom <= slot.bottom + 1e-4f, "${framing.dst} vs $slot")
                }
            }
        }
    }

    @Test
    fun `smart cropping stops before cutting a face`() {
        // A tall photo whose subject fills most of the height, dropped in a wide slot: covering the
        // slot completely would slice the person, so the fill has to give way.
        val tall = photo("tall", 1200, 2000, FocusArea(0.25f, 0.08f, 0.75f, 0.86f))
        val wideSlot = NormRect(0f, 0f, 1f, 0.4f)
        val fill = CropPlanner.fillFor(CropMode.SMART, tall, wideSlot, landscape, photosInScene = 1)
        assertTrue(fill < 0.9f, "fill was $fill")

        val framing = PhotoFraming.plan(tall, wideSlot, landscape, fill, zoom = 1f, pan = Vec2(1f, 1f))
        assertTrue(framing.src.top <= tall.focus.top + 1e-3f, "cuts the top: ${framing.src}")
        assertTrue(framing.src.bottom >= tall.focus.bottom - 1e-3f, "cuts the bottom: ${framing.src}")
    }

    @Test
    fun `smart cropping fills the slot when nothing important is at risk`() {
        val wide = photo("wide", 4000, 3000, FocusArea.point(0.5f, 0.5f))
        val slot = NormRect(0f, 0f, 0.5f, 1f)
        assertEquals(1f, CropPlanner.fillFor(CropMode.SMART, wide, slot, landscape, 1), 1e-3f)
    }

    @Test
    fun `automatic cropping treats photos differently`() {
        val slot = NormRect(0f, 0f, 1f, 1f)
        val fills = mixedPhotos(8).map { CropPlanner.fillFor(CropMode.AUTO, it, slot, landscape, 1) }
        assertTrue(fills.toSet().size > 1, "every photo got the same treatment: $fills")
        assertTrue(fills.all { it in 0f..1f })
    }

    @Test
    fun `automatic cropping fills more when the scene is crowded`() {
        val panorama = photo("panorama", 4000, 1200)
        val slot = NormRect(0f, 0f, 0.5f, 0.5f)
        val alone = CropPlanner.fillFor(CropMode.AUTO, panorama, slot, landscape, photosInScene = 1)
        val crowded = CropPlanner.fillFor(CropMode.AUTO, panorama, slot, landscape, photosInScene = 4)
        assertTrue(crowded > alone, "alone=$alone crowded=$crowded")
    }

    @Test
    fun `never cropping ignores the crop mode heuristics`() {
        mixedPhotos(8).forEach { photo ->
            assertEquals(
                0f,
                CropPlanner.fillFor(CropMode.NEVER, photo, NormRect(0f, 0f, 0.4f, 0.4f), landscape, 3),
            )
        }
    }

    @Test
    fun `the zoom ceiling keeps the focus area inside the crop`() {
        val subject = photo("subject", 3000, 2000, FocusArea(0.3f, 0.2f, 0.7f, 0.8f))
        val cropAspect = 16f / 9f
        val maxZoom = PhotoFraming.maxZoomKeepingFocus(subject, cropAspect)
        val crop = SmartCrop.crop(subject.aspect, cropAspect, maxZoom, Vec2(1f, 1f), subject.focus)
        assertTrue(crop.width >= subject.focus.width - 1e-3f, "crop $crop is narrower than the subject")
        assertTrue(crop.height >= subject.focus.height - 1e-3f, "crop $crop is shorter than the subject")
    }
}
