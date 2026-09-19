package com.example.slideshowstudio.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaletteTest {

    @Test
    fun `hex codes round trip`() {
        listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF3C6E9A.toInt(), 0xFF0E0E12.toInt()).forEach { color ->
            assertEquals(color, Palette.parseHex(Palette.toHex(color)))
        }
    }

    @Test
    fun `hex parsing accepts the usual forms`() {
        assertEquals(0xFFFFFFFF.toInt(), Palette.parseHex("#fff"))
        assertEquals(0xFFFFFFFF.toInt(), Palette.parseHex("FFFFFF"))
        assertEquals(0xFF102030.toInt(), Palette.parseHex("#102030"))
        assertEquals(0x80102030.toInt(), Palette.parseHex("#80102030"))
        assertEquals(0xFFAABBCC.toInt(), Palette.parseHex("  #aabbcc  "))
    }

    @Test
    fun `hex parsing rejects nonsense instead of guessing`() {
        listOf("", "#", "#12", "#12345", "zzzzzz", "#GG0000").forEach { input ->
            assertNull(Palette.parseHex(input), "accepted '$input'")
        }
    }

    @Test
    fun `colours blend component by component`() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val middle = Palette.lerpColor(black, white, 0.5f)
        assertEquals(128, Palette.red(middle))
        assertEquals(128, Palette.green(middle))
        assertEquals(128, Palette.blue(middle))
        assertEquals(255, Palette.alpha(middle))
        assertEquals(black, Palette.lerpColor(black, white, 0f))
        assertEquals(white, Palette.lerpColor(black, white, 1f))
    }

    @Test
    fun `random backgrounds stay dark and muted`() {
        val random = Random(7)
        repeat(200) {
            val color = Palette.randomBackground(random)
            val r = Palette.red(color)
            val g = Palette.green(color)
            val b = Palette.blue(color)
            val maxComponent = maxOf(r, g, b)
            val minComponent = minOf(r, g, b)
            val value = maxComponent / 255f
            val saturation = if (maxComponent == 0) 0f else (maxComponent - minComponent).toFloat() / maxComponent
            assertTrue(value <= 0.30f, "too bright: $value")
            assertTrue(saturation <= 0.36f, "too vivid: $saturation")
            assertEquals(255, Palette.alpha(color))
        }
    }

    @Test
    fun `consecutive random backgrounds are visibly different`() {
        val random = Random(3)
        var previous = Palette.randomBackground(random)
        repeat(50) {
            val next = Palette.randomBackground(random, Palette.hueOf(previous))
            val distance = kotlin.math.abs(Palette.hueOf(next) - Palette.hueOf(previous))
            val wrapped = if (distance > 180f) 360f - distance else distance
            assertTrue(wrapped >= 30f, "hues too close: $wrapped")
            previous = next
        }
    }

    @Test
    fun `colours survive a round trip through HSV`() {
        listOf(
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF3C6E9A.toInt(),
            0xFF8B2E2E.toInt(),
            0xFF2B2B33.toInt(),
        ).forEach { color ->
            val hsv = Palette.toHsv(color)
            val back = Palette.hsvToColor(hsv.hue, hsv.saturation, hsv.value)
            assertTrue(kotlin.math.abs(Palette.red(back) - Palette.red(color)) <= 1, Palette.toHex(back))
            assertTrue(kotlin.math.abs(Palette.green(back) - Palette.green(color)) <= 1, Palette.toHex(back))
            assertTrue(kotlin.math.abs(Palette.blue(back) - Palette.blue(color)) <= 1, Palette.toHex(back))
        }
    }

    @Test
    fun `grey has no saturation and black has no value`() {
        assertEquals(0f, Palette.toHsv(0xFF808080.toInt()).saturation, 1e-3f)
        assertEquals(0f, Palette.toHsv(0xFF000000.toInt()).value, 1e-3f)
        assertEquals(1f, Palette.toHsv(0xFFFFFFFF.toInt()).value, 1e-3f)
    }

    @Test
    fun `presets cover the obvious choices`() {
        assertTrue(0xFF000000.toInt() in Palette.PRESETS)
        assertTrue(0xFFFFFFFF.toInt() in Palette.PRESETS)
        assertTrue(Palette.PRESETS.size >= 6)
    }
}
