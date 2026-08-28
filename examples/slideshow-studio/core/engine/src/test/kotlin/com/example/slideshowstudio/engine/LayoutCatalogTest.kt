package com.example.slideshowstudio.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutCatalogTest {

    private val aspects = BOTH_FORMATS.map { it.aspect }

    @Test
    fun `every count offers several compositions in both orientations`() {
        aspects.forEach { aspect ->
            assertEquals(1, LayoutCatalog.templatesFor(1, aspect).size)
            for (count in 2..4) {
                assertTrue(LayoutCatalog.templatesFor(count, aspect).size >= 5, "aspect=$aspect count=$count")
            }
        }
    }

    @Test
    fun `portrait and landscape use different compositions`() {
        for (count in 2..4) {
            val landscape = LayoutCatalog.templatesFor(count, 16f / 9f).map { it.id }.toSet()
            val portrait = LayoutCatalog.templatesFor(count, 9f / 16f).map { it.id }.toSet()
            assertTrue(landscape.intersect(portrait).isEmpty(), "count=$count shares $landscape / $portrait")
        }
    }

    @Test
    fun `templates declare exactly the expected number of slots`() {
        aspects.forEach { aspect ->
            for (count in 1..4) {
                LayoutCatalog.templatesFor(count, aspect).forEach { template ->
                    assertEquals(count, template.count, template.id)
                }
            }
        }
    }

    @Test
    fun `slots stay inside the canvas and are big enough to be seen`() {
        aspects.forEach { aspect ->
            for (count in 1..4) {
                LayoutCatalog.templatesFor(count, aspect).forEach { template ->
                    template.slots.forEach { slot ->
                        assertTrue(slot.left >= -1e-4f && slot.top >= -1e-4f, "${template.id}: $slot")
                        assertTrue(slot.right <= 1f + 1e-4f && slot.bottom <= 1f + 1e-4f, "${template.id}: $slot")
                        assertTrue(slot.width > 0.1f && slot.height > 0.1f, "${template.id}: $slot")
                    }
                }
            }
        }
    }

    @Test
    fun `slots never overlap`() {
        aspects.forEach { aspect ->
            for (count in 1..4) {
                LayoutCatalog.templatesFor(count, aspect).forEach { template ->
                    val slots = template.slots
                    for (i in slots.indices) {
                        for (j in i + 1 until slots.size) {
                            val overlapW = min(slots[i].right, slots[j].right) - max(slots[i].left, slots[j].left)
                            val overlapH = min(slots[i].bottom, slots[j].bottom) - max(slots[i].top, slots[j].top)
                            assertTrue(
                                overlapW <= 1e-4f || overlapH <= 1e-4f,
                                "${template.id}: slots $i and $j overlap",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `slot shapes stay usable in both orientations`() {
        aspects.forEach { aspect ->
            for (count in 1..4) {
                LayoutCatalog.templatesFor(count, aspect).forEach { template ->
                    template.slots.forEach { slot ->
                        val pixelAspect = slot.pixelAspect(aspect)
                        assertTrue(pixelAspect in 0.3f..4.5f, "${template.id}: extreme slot $pixelAspect")
                    }
                }
            }
        }
    }

    @Test
    fun `portrait compositions favour stacking`() {
        // In 9:16 the room is vertical: two photos should be offered mostly as top and bottom bands.
        val templates = LayoutCatalog.templatesFor(2, 9f / 16f)
        val stacked = templates.count { template ->
            val (first, second) = template.slots
            second.top >= first.bottom - 1e-3f
        }
        assertTrue(stacked >= templates.size - 1, "only $stacked of ${templates.size} are stacked")
    }

    @Test
    fun `landscape compositions favour side by side`() {
        val templates = LayoutCatalog.templatesFor(2, 16f / 9f)
        val sideBySide = templates.count { template ->
            val (first, second) = template.slots
            second.left >= first.right - 1e-3f
        }
        assertTrue(sideBySide >= 3, "only $sideBySide of ${templates.size} are side by side")
    }

    @Test
    fun `the single photo layout is full bleed`() {
        aspects.forEach { aspect ->
            assertEquals(NormRect.Full, LayoutCatalog.templatesFor(1, aspect).single().slots.single())
        }
    }
}
