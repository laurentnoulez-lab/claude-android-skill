package com.example.slideshowstudio.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutCatalogTest {

    private val aspect = 16f / 9f

    @Test
    fun `every count offers several compositions`() {
        assertEquals(1, LayoutCatalog.templatesFor(1, aspect).size)
        for (count in 2..4) {
            assertTrue(LayoutCatalog.templatesFor(count, aspect).size >= 5, "count=$count")
        }
    }

    @Test
    fun `templates declare exactly the expected number of slots`() {
        for (count in 1..4) {
            LayoutCatalog.templatesFor(count, aspect).forEach { template ->
                assertEquals(count, template.count, template.id)
            }
        }
    }

    @Test
    fun `slots stay inside the canvas and are big enough to be seen`() {
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

    @Test
    fun `slots never overlap`() {
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

    @Test
    fun `slot aspect ratios stay reasonable`() {
        for (count in 1..4) {
            LayoutCatalog.templatesFor(count, aspect).forEach { template ->
                template.slots.forEach { slot ->
                    val pixelAspect = slot.pixelAspect(aspect)
                    assertTrue(pixelAspect in 0.35f..4.5f, "${template.id}: extreme slot $pixelAspect")
                }
            }
        }
    }

    @Test
    fun `the single photo layout is full bleed`() {
        assertEquals(NormRect.Full, LayoutCatalog.templatesFor(1, aspect).single().slots.single())
    }
}
