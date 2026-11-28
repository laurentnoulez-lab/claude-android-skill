package io.quarantaine.gfx

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Identifiants des textures du jeu. Toutes sont générées procéduralement (aucun fichier externe).
 * Convention monde : alpha < 1 => texel émissif (lumineux dans le noir).
 */
object Tex {
    const val TECH = 0
    const val ROCK = 1
    const val COMPUTER = 2
    const val PIPES = 3
    const val HAZARD = 4
    const val FLESH = 5
    const val CRATE = 6
    const val CRATE_TOP = 7
    const val EXIT = 8
    const val DOOR = 9
    const val DOOR_RED = 10
    const val DOOR_BLUE = 11
    const val DOOR_YELLOW = 12
    const val FLOOR_TILE = 13
    const val FLOOR_GRATE = 14
    const val FLOOR_PLATE = 15
    const val STEP = 16
    const val CEIL = 17
    const val CEIL_LIGHT = 18
    const val LAVA = 19
    const val GROUND = 20
    const val FLOOR_FLESH = 21
    const val WHITE = 22
    const val SKIN = 23
    const val METAL = 24
    const val GLOW = 25
    const val COUNT = 26

    const val SIZE = 64

    fun generate(id: Int): Img = when (id) {
        TECH -> tech()
        ROCK -> rock()
        COMPUTER -> computer()
        PIPES -> pipes()
        HAZARD -> hazard()
        FLESH -> flesh(Img(SIZE, SIZE), 1f)
        CRATE -> crate(true)
        CRATE_TOP -> crate(false)
        EXIT -> exit()
        DOOR -> door(-1f, 0f, 0f)
        DOOR_RED -> door(1f, 0.15f, 0.1f)
        DOOR_BLUE -> door(0.2f, 0.45f, 1f)
        DOOR_YELLOW -> door(1f, 0.85f, 0.15f)
        FLOOR_TILE -> floorTile()
        FLOOR_GRATE -> grate()
        FLOOR_PLATE -> plate()
        STEP -> step()
        CEIL -> ceil(false)
        CEIL_LIGHT -> ceil(true)
        LAVA -> lava()
        GROUND -> ground()
        FLOOR_FLESH -> flesh(floorTile(), 0.55f)
        WHITE -> Img(SIZE, SIZE).apply { fill(1f, 1f, 1f) }
        SKIN -> skin()
        METAL -> metal()
        GLOW -> glow()
        else -> Img(SIZE, SIZE).apply { fill(1f, 0f, 1f) }
    }

    private fun tech(): Img {
        val im = Img(SIZE, SIZE)
        im.fill(0.34f, 0.38f, 0.44f)
        im.grain(11, 0.10f)
        // Trois bandes : panneau haut, rainure centrale, panneau bas
        im.bevel(0, 0, 64, 22); im.bevel(0, 42, 64, 64)
        im.bevel(2, 2, 30, 20, 1.15f, 0.8f); im.bevel(34, 2, 62, 20, 1.15f, 0.8f)
        im.rectMul(0, 22, 64, 42, 0.55f)
        for (x in 0 until 64) { im.mulPx(x, 22, 0.5f); im.mulPx(x, 41, 1.4f) }
        im.rect(4, 29, 60, 35, 0.16f, 0.18f, 0.2f)
        // Témoins lumineux dans la rainure
        for (i in 0 until 4) im.rect(8 + i * 14, 30, 12 + i * 14, 34, 0.25f, 0.85f, 1f, if (i % 2 == 0) 0.35f else 1f)
        im.bevel(46, 46, 60, 60, 0.8f, 1.2f)
        for ((x, y) in listOf(3 to 45, 60 to 45, 3 to 60, 60 to 60, 31 to 4, 31 to 17)) {
            im.set(x, y, 0.62f, 0.66f, 0.72f); im.set(x + 1, y + 1, 0.15f, 0.16f, 0.18f)
        }
        return im
    }

    private fun rock(): Img {
        val im = Img(SIZE, SIZE)
        for (y in 0 until 64) for (x in 0 until 64) {
            val n = Noise.fbm(x / 16f, y / 16f, 3, 4, 4f)
            val m = Noise.fbm(x / 8f, y / 8f, 9, 3, 8f)
            val t = (n * 1.4f - 0.2f).coerceIn(0f, 1f)
            var r = 0.32f + 0.30f * t; var g = 0.24f + 0.24f * t; var b = 0.12f + 0.08f * t
            if (abs(m - 0.5f) < 0.025f) { r *= 0.45f; g *= 0.45f; b *= 0.45f }
            if (m > 0.72f) { r += 0.12f; g += 0.1f; b += 0.02f }
            im.set(x, y, r, g, b)
        }
        im.grain(5, 0.12f)
        return im
    }

    private fun computer(): Img {
        val im = Img(SIZE, SIZE)
        im.fill(0.17f, 0.19f, 0.21f)
        im.grain(21, 0.08f)
        im.bevel(0, 0, 64, 64)
        // Écran
        im.rect(7, 7, 57, 35, 0.02f, 0.06f, 0.04f)
        im.bevel(6, 6, 58, 36, 0.5f, 1.4f)
        val rng = io.quarantaine.math.Rng(77)
        for (line in 0 until 6) {
            val len = 10 + rng.int(34)
            for (x in 10 until 10 + len) if (rng.float() > 0.2f) im.set(x, 10 + line * 4, 0.3f, 1f, 0.55f, 0.25f)
        }
        // Courbe de signal
        for (x in 10 until 54) {
            val y = (30 + sin(x * 0.45f) * 2.5f).toInt()
            im.set(x, y, 0.35f, 0.9f, 1f, 0.3f)
        }
        // Boutons
        val cols = arrayOf(floatArrayOf(1f, 0.2f, 0.15f), floatArrayOf(1f, 0.8f, 0.1f), floatArrayOf(0.2f, 1f, 0.3f))
        for (i in 0 until 6) {
            val c = cols[i % 3]
            im.rect(9 + i * 8, 42, 14 + i * 8, 46, c[0], c[1], c[2], 0.45f)
        }
        im.rect(8, 50, 56, 58, 0.1f, 0.11f, 0.12f)
        for (x in 10 until 54 step 3) im.rect(x, 52, x + 2, 56, 0.3f, 0.32f, 0.34f)
        return im
    }

    private fun pipes(): Img {
        val im = Img(SIZE, SIZE)
        im.fill(0.1f, 0.1f, 0.11f)
        val pipesX = intArrayOf(2, 22, 44)
        val widths = intArrayOf(16, 18, 16)
        val copper = booleanArrayOf(true, false, true)
        for (p in 0..2) {
            val x0 = pipesX[p]; val w = widths[p]
            for (x in x0 until x0 + w) {
                val u = (x - x0 + 0.5f) / w
                val shade = 0.35f + 0.85f * sqrt(sin(u * 3.14159f).coerceAtLeast(0f))
                for (y in 0 until 64) {
                    if (copper[p]) im.set(x, y, 0.55f * shade, 0.33f * shade, 0.18f * shade)
                    else im.set(x, y, 0.45f * shade, 0.47f * shade, 0.5f * shade)
                }
            }
            for (fy in intArrayOf(8, 40)) {
                im.rectMul(x0 - 1, fy, x0 + w + 1, fy + 5, 1.25f)
                for (x in x0 - 1 until x0 + w + 1) im.mulPx(x, fy + 5, 0.4f)
            }
        }
        im.grain(31, 0.1f)
        return im
    }

    private fun stripes(im: Img, y0: Int, y1: Int) {
        for (y in y0 until y1) for (x in 0 until 64) {
            if (((x + y) / 8) % 2 == 0) im.set(x, y, 0.85f, 0.7f, 0.1f) else im.set(x, y, 0.08f, 0.08f, 0.08f)
        }
    }

    private fun hazard(): Img {
        val im = Img(SIZE, SIZE)
        im.fill(0.4f, 0.4f, 0.42f)
        im.grain(41, 0.12f)
        stripes(im, 22, 42)
        im.bevel(0, 0, 64, 22); im.bevel(0, 42, 64, 64); im.bevel(0, 22, 64, 42, 0.7f, 1.3f)
        return im
    }

    private fun flesh(base: Img, coverage: Float): Img {
        val im = base
        for (y in 0 until 64) for (x in 0 until 64) {
            val n = Noise.fbm(x / 16f, y / 16f, 51, 4, 4f)
            if (n > 1f - coverage) {
                val v = Noise.fbm(x / 8f, y / 8f, 57, 3, 8f)
                var r = 0.42f + 0.3f * n; var g = 0.07f + 0.1f * n; var b = 0.1f + 0.12f * n
                if (abs(v - 0.5f) < 0.03f) { r = 0.2f; g = 0.02f; b = 0.12f }
                val edge = ((n - (1f - coverage)) * 8f).coerceIn(0f, 1f)
                im.blend(x, y, r, g, b, edge)
            }
        }
        val rng = io.quarantaine.math.Rng(91)
        val count = if (coverage >= 1f) 3 else 2
        for (i in 0 until count) {
            val cx = rng.range(6f, 58f); val cy = rng.range(6f, 58f)
            if (Noise.fbm(cx / 16f, cy / 16f, 51, 4, 4f) > 1f - coverage) {
                im.disc(cx, cy, rng.range(1.5f, 2.6f), 0.75f, 0.85f, 0.3f, 0.75f)
            }
        }
        im.grain(53, 0.1f)
        return im
    }

    private fun crate(side: Boolean): Img {
        val im = Img(SIZE, SIZE)
        im.fill(0.30f, 0.35f, 0.2f)
        im.grain(61, 0.14f)
        im.rectMul(0, 0, 64, 5, 0.7f); im.rectMul(0, 59, 64, 64, 0.7f)
        im.rectMul(0, 5, 5, 59, 0.7f); im.rectMul(59, 5, 64, 59, 0.7f)
        im.bevel(0, 0, 64, 64); im.bevel(5, 5, 59, 59, 0.7f, 1.3f)
        if (side) {
            for (i in 6 until 58) {
                for (d in -1..1) { im.mulPx(i, i + d, 0.75f); im.mulPx(i, 63 - i + d, 0.75f) }
            }
            Font.drawInto(im, "Q7", 26, 26, 0.85f, 0.85f, 0.75f, 1f)
        } else {
            im.rect(24, 24, 40, 40, 0.75f, 0.6f, 0.12f)
            Font.drawInto(im, "!", 30, 28, 0.1f, 0.1f, 0.1f, 1f)
        }
        return im
    }

    private fun exit(): Img {
        val im = tech()
        im.rect(4, 6, 60, 30, 0.03f, 0.05f, 0.04f)
        im.bevel(3, 5, 61, 31, 0.5f, 1.4f)
        Font.drawInto(im, "SORTIE", 15, 14, 0.3f, 1f, 0.4f, 0.0f)
        // Flèche
        for (i in 0 until 8) im.rect(28 - i, 44 + i, 36 + i, 45 + i, 0.3f, 1f, 0.4f, 0.1f)
        im.rect(29, 36, 35, 44, 0.3f, 1f, 0.4f, 0.1f)
        return im
    }

    private fun door(cr: Float, cg: Float, cb: Float): Img {
        val im = Img(SIZE, SIZE)
        im.fill(0.40f, 0.42f, 0.47f)
        im.grain(71, 0.1f)
        for (y in intArrayOf(4, 16, 44)) { im.rectMul(2, y, 62, y + 8, 1.1f); im.bevel(2, y, 62, y + 8) }
        stripes(im, 56, 64)
        for (y in 0 until 56) { im.mulPx(31, y, 0.3f); im.mulPx(32, y, 0.5f) }
        if (cr >= 0f) {
            im.rect(2, 28, 62, 36, cr, cg, cb, 0.25f)
            im.rect(0, 0, 2, 64, cr * 0.8f, cg * 0.8f, cb * 0.8f, 0.6f)
            im.rect(62, 0, 64, 64, cr * 0.8f, cg * 0.8f, cb * 0.8f, 0.6f)
        } else {
            im.rect(2, 28, 62, 36, 0.25f, 0.27f, 0.3f)
            im.rect(26, 30, 38, 34, 0.3f, 0.9f, 1f, 0.4f)
        }
        im.bevel(0, 0, 64, 64)
        return im
    }

    private fun floorTile(): Img {
        val im = Img(SIZE, SIZE)
        for (ty in 0 until 4) for (tx in 0 until 4) {
            val v = 0.36f + Noise.hash(tx, ty, 81) * 0.08f
            im.rect(tx * 16, ty * 16, tx * 16 + 16, ty * 16 + 16, v, v, v * 1.04f)
            im.bevel(tx * 16, ty * 16, tx * 16 + 16, ty * 16 + 16, 1.12f, 0.55f)
        }
        im.grain(83, 0.12f)
        return im
    }

    private fun grate(): Img {
        val im = Img(SIZE, SIZE)
        im.fill(0.05f, 0.05f, 0.055f)
        for (y in 0 until 64) for (x in 0 until 64) {
            val bx = x % 8 < 2; val by = y % 8 < 2
            if (bx || by) {
                val v = if (bx && by) 0.5f else 0.4f
                im.set(x, y, v, v, v * 1.05f)
            }
        }
        im.grain(87, 0.15f)
        return im
    }

    private fun plate(): Img {
        val im = Img(SIZE, SIZE)
        im.fill(0.44f, 0.46f, 0.49f)
        im.grain(91, 0.08f)
        for (cy in 0 until 8) for (cx in 0 until 8) {
            val ox = cx * 8 + if (cy % 2 == 0) 0 else 4
            val oy = cy * 8
            for (i in 0 until 4) { im.mulPx(ox + 2 + i, oy + 5 - i, 1.35f); im.mulPx(ox + 3 + i, oy + 5 - i, 0.7f) }
        }
        im.bevel(0, 0, 64, 64, 1.1f, 0.7f)
        return im
    }

    private fun step(): Img {
        val im = Img(SIZE, SIZE)
        im.fill(0.38f, 0.39f, 0.41f)
        im.grain(93, 0.12f)
        stripes(im, 0, 10)
        for (x in 0 until 64) im.mulPx(x, 10, 0.4f)
        for (y in 12 until 64 step 13) im.bevel(0, y, 64, y + 12, 1.1f, 0.75f)
        return im
    }

    private fun ceil(light: Boolean): Img {
        val im = Img(SIZE, SIZE)
        im.fill(0.2f, 0.21f, 0.23f)
        im.grain(97, 0.1f)
        im.bevel(0, 0, 32, 32); im.bevel(32, 0, 64, 32); im.bevel(0, 32, 32, 64); im.bevel(32, 32, 64, 64)
        if (light) {
            im.rect(12, 12, 52, 52, 0.12f, 0.12f, 0.13f)
            im.rect(14, 14, 50, 50, 0.92f, 0.96f, 1f, 0f)
            for (x in 14 until 50 step 6) im.rect(x, 14, x + 1, 50, 0.7f, 0.75f, 0.8f, 0.2f)
        } else {
            for (y in 40 until 58 step 4) im.rect(38, y, 58, y + 2, 0.06f, 0.06f, 0.07f)
        }
        return im
    }

    private fun lava(): Img {
        val im = Img(SIZE, SIZE)
        for (y in 0 until 64) for (x in 0 until 64) {
            val n = Noise.fbm(x / 16f, y / 16f, 101, 4, 4f)
            val m = Noise.fbm(x / 8f + 3f, y / 8f, 107, 2, 8f)
            val t = (n * 1.3f - 0.15f + (m - 0.5f) * 0.3f).coerceIn(0f, 1f)
            val r: Float; val g: Float; val b: Float
            if (t < 0.45f) { val k = t / 0.45f; r = 0.25f + 0.5f * k; g = 0.05f + 0.15f * k; b = 0.02f }
            else { val k = (t - 0.45f) / 0.55f; r = 0.75f + 0.25f * k; g = 0.2f + 0.65f * k; b = 0.02f + 0.3f * k }
            im.set(x, y, r, g, b, 1f - (0.35f + 0.6f * t))
        }
        return im
    }

    private fun ground(): Img {
        val im = Img(SIZE, SIZE)
        for (y in 0 until 64) for (x in 0 until 64) {
            val n = Noise.fbm(x / 16f, y / 16f, 111, 4, 4f)
            val m = Noise.fbm(x / 8f, y / 8f, 117, 3, 8f)
            var r = 0.62f; var g = 0.52f; var b = 0.22f
            if (n > 0.58f) { val k = ((n - 0.58f) * 6f).coerceIn(0f, 1f); r += (0.62f - r) * k; g += (0.32f - g) * k; b += (0.12f - b) * k }
            if (n < 0.38f) { val k = ((0.38f - n) * 6f).coerceIn(0f, 1f); r += (0.78f - r) * k; g += (0.76f - g) * k; b += (0.62f - b) * k }
            if (m < 0.3f) { r *= 0.6f; g *= 0.55f; b *= 0.5f }
            im.set(x, y, r, g, b)
        }
        im.grain(119, 0.14f)
        return im
    }

    private fun skin(): Img {
        val im = Img(SIZE, SIZE)
        for (y in 0 until 64) for (x in 0 until 64) {
            val n = Noise.fbm(x / 8f, y / 8f, 121, 3, 8f)
            val v = 0.7f + 0.3f * n
            im.set(x, y, v, v, v)
        }
        val rng = io.quarantaine.math.Rng(123)
        for (i in 0 until 14) im.disc(rng.range(0f, 64f), rng.range(0f, 64f), rng.range(1f, 2.5f), 0.55f, 0.55f, 0.55f)
        return im
    }

    private fun metal(): Img {
        val im = Img(SIZE, SIZE)
        for (y in 0 until 64) {
            val row = 0.78f + Noise.hash(0, y, 131) * 0.12f
            for (x in 0 until 64) {
                val v = row + (Noise.hash(x, y, 133) - 0.5f) * 0.06f
                im.set(x, y, v, v, v * 1.03f)
            }
        }
        im.bevel(0, 0, 64, 64, 1.15f, 0.7f)
        im.bevel(0, 0, 64, 32, 1.1f, 0.8f)
        return im
    }

    private fun glow(): Img {
        val im = Img(SIZE, SIZE)
        for (y in 0 until 64) for (x in 0 until 64) {
            val dx = (x + 0.5f - 32f) / 32f; val dy = (y + 0.5f - 32f) / 32f
            val d = sqrt(dx * dx + dy * dy)
            val a = (1f - d).coerceIn(0f, 1f)
            im.set(x, y, 1f, 1f, 1f, a * a)
        }
        return im
    }
}
