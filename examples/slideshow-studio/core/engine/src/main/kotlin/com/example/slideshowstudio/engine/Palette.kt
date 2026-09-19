package com.example.slideshowstudio.engine

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Colours, as packed ARGB integers so the engine stays free of any Android type.
 *
 * Background colours are deliberately dark and desaturated: a bright or vivid background pulls the
 * eye away from the photos and makes their edges look harsh.
 */
object Palette {

    const val DEFAULT_BACKGROUND: Int = 0xFF0E0E12.toInt()

    /** Ready-made choices offered next to the colour picker. */
    val PRESETS: List<Int> = listOf(
        0xFF000000.toInt(), // noir
        0xFF0E0E12.toInt(), // presque noir
        0xFF2B2B33.toInt(), // gris anthracite
        0xFF6E6E78.toInt(), // gris moyen
        0xFFE6E6EA.toInt(), // gris clair
        0xFFFFFFFF.toInt(), // blanc
        0xFF1B2A3A.toInt(), // bleu nuit
        0xFF2A2233.toInt(), // prune
        0xFF223229.toInt(), // vert forêt
        0xFF33251C.toInt(), // brun chaud
    )

    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        ((alpha and 0xFF) shl 24) or ((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF)

    fun alpha(color: Int): Int = (color ushr 24) and 0xFF
    fun red(color: Int): Int = (color ushr 16) and 0xFF
    fun green(color: Int): Int = (color ushr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF

    /** Accepts `#RGB`, `#RRGGBB` and `#AARRGGBB`, with or without the leading `#`. */
    fun parseHex(text: String): Int? {
        val cleaned = text.trim().removePrefix("#")
        if (cleaned.any { it.digitToIntOrNull(16) == null }) return null
        return when (cleaned.length) {
            3 -> {
                val r = cleaned[0].digitToInt(16) * 17
                val g = cleaned[1].digitToInt(16) * 17
                val b = cleaned[2].digitToInt(16) * 17
                argb(255, r, g, b)
            }

            6 -> argb(
                255,
                cleaned.substring(0, 2).toInt(16),
                cleaned.substring(2, 4).toInt(16),
                cleaned.substring(4, 6).toInt(16),
            )

            8 -> argb(
                cleaned.substring(0, 2).toInt(16),
                cleaned.substring(2, 4).toInt(16),
                cleaned.substring(4, 6).toInt(16),
                cleaned.substring(6, 8).toInt(16),
            )

            else -> null
        }
    }

    /** Formats as `#RRGGBB`, the form people type back in. */
    fun toHex(color: Int): String {
        fun component(value: Int) = value.toString(16).uppercase().padStart(2, '0')
        return "#${component(red(color))}${component(green(color))}${component(blue(color))}"
    }

    fun lerpColor(start: Int, stop: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int) = (a + (b - a) * t).roundToInt().coerceIn(0, 255)
        return argb(
            mix(alpha(start), alpha(stop)),
            mix(red(start), red(stop)),
            mix(green(start), green(stop)),
            mix(blue(start), blue(stop)),
        )
    }

    /**
     * Colour of a scene background in random mode: any hue, but always muted and dark, and always
     * far enough from the previous one for the change to read as intentional.
     */
    fun randomBackground(random: Random, previousHue: Float? = null): Int {
        var hue = random.nextFloat() * 360f
        if (previousHue != null) {
            var attempts = 0
            while (hueDistance(hue, previousHue) < MIN_HUE_DISTANCE && attempts < 8) {
                hue = random.nextFloat() * 360f
                attempts++
            }
        }
        val saturation = 0.14f + random.nextFloat() * 0.18f
        val value = 0.13f + random.nextFloat() * 0.13f
        return hsvToColor(hue, saturation, value)
    }

    /** Hue in degrees, saturation and value in 0..1. */
    data class Hsv(val hue: Float, val saturation: Float, val value: Float)

    fun toHsv(color: Int): Hsv {
        val r = red(color) / 255f
        val g = green(color) / 255f
        val b = blue(color) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val saturation = if (max <= 0f) 0f else (max - min) / max
        return Hsv(hue = hueOf(color), saturation = saturation, value = max)
    }

    fun hueOf(color: Int): Float {
        val r = red(color) / 255f
        val g = green(color) / 255f
        val b = blue(color) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta < 1e-4f) return 0f
        val hue = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return (hue + 360f) % 360f
    }

    fun hsvToColor(hue: Float, saturation: Float, value: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb(
            255,
            ((r + m) * 255f).roundToInt(),
            ((g + m) * 255f).roundToInt(),
            ((b + m) * 255f).roundToInt(),
        )
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val diff = abs(a - b) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    private const val MIN_HUE_DISTANCE = 35f
}
