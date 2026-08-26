package com.example.slideshowstudio.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SmartCropTest {

    private val imageAspects = listOf(4f / 3f, 3f / 4f, 1f, 16f / 9f, 9f / 16f, 2.35f, 0.5f)
    private val targetAspects = listOf(16f / 9f, 8f / 9f, 1f, 32f / 9f, 0.6f, 2f)

    @Test
    fun `crop always matches the target aspect ratio so photos are never distorted`() {
        for (image in imageAspects) {
            for (target in targetAspects) {
                for (zoom in listOf(1f, 1.05f, 1.2f, 1.35f)) {
                    val crop = SmartCrop.crop(image, target, zoom, Vec2(0.3f, -0.4f), FocusArea.point(0.5f, 0.5f))
                    val actual = (crop.width / crop.height) * image
                    assertTrue(
                        abs(actual - target) < 1e-3f,
                        "image=$image target=$target zoom=$zoom -> $actual",
                    )
                }
            }
        }
    }

    @Test
    fun `crop stays inside the source image`() {
        for (image in imageAspects) {
            for (target in targetAspects) {
                for (pan in listOf(Vec2(-1f, -1f), Vec2(1f, 1f), Vec2(0f, 0f), Vec2(-1f, 1f))) {
                    val crop = SmartCrop.crop(image, target, 1.25f, pan, FocusArea(0.1f, 0.05f, 0.3f, 0.25f))
                    assertTrue(crop.left >= -1e-4f, "left ${crop.left}")
                    assertTrue(crop.top >= -1e-4f, "top ${crop.top}")
                    assertTrue(crop.right <= 1f + 1e-4f, "right ${crop.right}")
                    assertTrue(crop.bottom <= 1f + 1e-4f, "bottom ${crop.bottom}")
                }
            }
        }
    }

    @Test
    fun `focus area is kept visible when it fits in the crop`() {
        val focus = FocusArea(0.6f, 0.05f, 0.8f, 0.25f) // a face in the top right corner
        for (pan in listOf(Vec2(-1f, -1f), Vec2(1f, 1f), Vec2(0f, 0f))) {
            val crop = SmartCrop.crop(
                imageAspect = 3f / 4f,
                targetAspect = 16f / 9f,
                zoom = 1.05f,
                pan = pan,
                focus = focus,
            )
            if (crop.width >= focus.width && crop.height >= focus.height) {
                assertTrue(crop.left <= focus.left + 1e-4f, "crop cuts the focus on the left: $crop")
                assertTrue(crop.right >= focus.right - 1e-4f, "crop cuts the focus on the right: $crop")
                assertTrue(crop.top <= focus.top + 1e-4f, "crop cuts the focus on top: $crop")
                assertTrue(crop.bottom >= focus.bottom - 1e-4f, "crop cuts the focus at the bottom: $crop")
            }
        }
    }

    @Test
    fun `panning actually moves the crop when there is room`() {
        val left = SmartCrop.crop(16f / 9f, 16f / 9f, 1.2f, Vec2(-1f, 0f), FocusArea.point(0.5f, 0.5f))
        val right = SmartCrop.crop(16f / 9f, 16f / 9f, 1.2f, Vec2(1f, 0f), FocusArea.point(0.5f, 0.5f))
        assertTrue(right.centerX - left.centerX > 0.05f, "pan produced no movement: $left / $right")
    }
}
