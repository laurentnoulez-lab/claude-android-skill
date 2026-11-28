package io.quarantaine.gfx

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.sqrt

/** Image RGBA en mémoire (valeurs flottantes 0..1), utilisée pour générer les textures procédurales. */
class Img(val w: Int, val h: Int) {
    val r = FloatArray(w * h)
    val g = FloatArray(w * h)
    val b = FloatArray(w * h)
    val a = FloatArray(w * h) { 1f }

    fun idx(x: Int, y: Int) = ((y % h + h) % h) * w + ((x % w + w) % w)

    fun set(x: Int, y: Int, cr: Float, cg: Float, cb: Float, ca: Float = 1f) {
        val i = idx(x, y); r[i] = cr; g[i] = cg; b[i] = cb; a[i] = ca
    }

    fun blend(x: Int, y: Int, cr: Float, cg: Float, cb: Float, t: Float) {
        val i = idx(x, y)
        r[i] += (cr - r[i]) * t; g[i] += (cg - g[i]) * t; b[i] += (cb - b[i]) * t
    }

    fun mulPx(x: Int, y: Int, f: Float) { val i = idx(x, y); r[i] *= f; g[i] *= f; b[i] *= f }

    fun fill(cr: Float, cg: Float, cb: Float, ca: Float = 1f) {
        for (i in 0 until w * h) { r[i] = cr; g[i] = cg; b[i] = cb; a[i] = ca }
    }

    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, cr: Float, cg: Float, cb: Float, ca: Float = 1f) {
        for (y in y0 until y1) for (x in x0 until x1) set(x, y, cr, cg, cb, ca)
    }

    fun rectMul(x0: Int, y0: Int, x1: Int, y1: Int, f: Float) {
        for (y in y0 until y1) for (x in x0 until x1) mulPx(x, y, f)
    }

    /** Rectangle en relief : bord clair en haut/gauche, sombre en bas/droite. */
    fun bevel(x0: Int, y0: Int, x1: Int, y1: Int, light: Float = 1.25f, dark: Float = 0.6f) {
        for (x in x0 until x1) { mulPx(x, y0, light); mulPx(x, y1 - 1, dark) }
        for (y in y0 + 1 until y1 - 1) { mulPx(x0, y, light); mulPx(x1 - 1, y, dark) }
    }

    fun disc(cx: Float, cy: Float, rad: Float, cr: Float, cg: Float, cb: Float, ca: Float = 1f) {
        val x0 = floor(cx - rad - 1).toInt(); val x1 = (cx + rad + 1).toInt()
        val y0 = floor(cy - rad - 1).toInt(); val y1 = (cy + rad + 1).toInt()
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            val d = sqrt(dx * dx + dy * dy)
            val cov = (rad + 0.5f - d).coerceIn(0f, 1f)
            if (cov > 0f) {
                val i = idx(x, y)
                r[i] += (cr - r[i]) * cov; g[i] += (cg - g[i]) * cov; b[i] += (cb - b[i]) * cov
                a[i] += (ca - a[i]) * cov
            }
        }
    }

    /** Applique un bruit multiplicatif pour casser l'uniformité. */
    fun grain(seed: Int, amount: Float, scale: Float = 1f) {
        for (y in 0 until h) for (x in 0 until w) {
            val n = (Noise.hash(x, y, seed) - 0.5f) * amount + (Noise.fbm(x * scale / 16f, y * scale / 16f, seed + 7, 3, w * scale / 16f) - 0.5f) * amount
            mulPx(x, y, 1f + n)
        }
    }

    fun toRgbaBuffer(): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until w * h) {
            buf.put(c8(r[i])); buf.put(c8(g[i])); buf.put(c8(b[i])); buf.put(c8(a[i]))
        }
        buf.flip()
        return buf
    }

    /** Réduction par 2 (pour les niveaux de mipmap générés manuellement si besoin). */
    fun half(): Img {
        val o = Img(maxOf(1, w / 2), maxOf(1, h / 2))
        for (y in 0 until o.h) for (x in 0 until o.w) {
            var sr = 0f; var sg = 0f; var sb = 0f; var sa = 0f
            for (dy in 0..1) for (dx in 0..1) {
                val i = idx(x * 2 + dx, y * 2 + dy)
                sr += r[i]; sg += g[i]; sb += b[i]; sa += a[i]
            }
            o.set(x, y, sr / 4f, sg / 4f, sb / 4f, sa / 4f)
        }
        return o
    }

    private fun c8(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
}

/** Bruit de valeur périodique (pour des textures qui se répètent sans couture). */
object Noise {
    fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    /** Bruit lissé périodique de période [period] (en unités de cellule). */
    fun value(x: Float, y: Float, seed: Int, period: Int): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val fx = x - xi; val fy = y - yi
        val p = if (period <= 0) Int.MAX_VALUE else period
        fun hp(ix: Int, iy: Int) = hash(Math.floorMod(ix, p), Math.floorMod(iy, p), seed)
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        val a = hp(xi, yi); val b = hp(xi + 1, yi); val c = hp(xi, yi + 1); val d = hp(xi + 1, yi + 1)
        return (a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy
    }

    fun fbm(x: Float, y: Float, seed: Int, octaves: Int, period: Float): Float {
        var sum = 0f; var amp = 0.5f; var f = 1f; var norm = 0f
        for (o in 0 until octaves) {
            sum += value(x * f, y * f, seed + o * 31, (period * f).toInt()) * amp
            norm += amp; amp *= 0.5f; f *= 2f
        }
        return sum / norm
    }
}
