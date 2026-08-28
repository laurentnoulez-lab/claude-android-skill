package com.example.slideshowstudio.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class MotionSpecTest {

    @Test
    fun `movements stay inside their legal range even when extrapolated during a transition`() {
        val random = Random(1)
        MotionKind.entries.forEach { kind ->
            repeat(50) {
                val spec = MotionFactory.create(kind, random)
                var progress = 0f
                while (progress <= 1.5f) {
                    val zoom = spec.zoomAt(progress)
                    val pan = spec.panAt(progress)
                    assertTrue(zoom in 1f..MotionSpec.MAX_ZOOM, "$kind zoom=$zoom at $progress")
                    assertTrue(pan.x in -1f..1f && pan.y in -1f..1f, "$kind pan=$pan at $progress")
                    progress += 0.05f
                }
            }
        }
    }

    @Test
    fun `movements keep travelling instead of freezing before the transition ends`() {
        val random = Random(2)
        MotionKind.entries.forEach { kind ->
            val spec = MotionFactory.create(kind, random)
            val a = spec.zoomAt(1f) to spec.panAt(1f)
            val b = spec.zoomAt(1.3f) to spec.panAt(1.3f)
            val moved = a.first != b.first || a.second != b.second
            assertTrue(moved, "$kind freezes during the trailing transition")
        }
    }

    @Test
    fun `zoom amplitude stays subtle`() {
        val random = Random(3)
        MotionKind.entries.forEach { kind ->
            repeat(30) {
                val spec = MotionFactory.create(kind, random)
                val amplitude = kotlin.math.abs(spec.endZoom - spec.startZoom)
                assertTrue(amplitude <= 0.15f, "$kind zooms too much: $amplitude")
                assertTrue(kotlin.math.abs(spec.startRotationDeg) <= MotionSpec.MAX_ROTATION)
            }
        }
    }

    @Test
    fun `rotation cover scale keeps the slot filled`() {
        assertTrue(rotationCoverScale(0f, 100f, 100f) == 1f)
        assertTrue(rotationCoverScale(1.5f, 1920f, 1080f) > 1f)
        assertTrue(rotationCoverScale(1.5f, 1920f, 1080f) < 1.06f)
        assertTrue(rotationCoverScale(-1.5f, 400f, 1000f) > 1f)
    }
}
