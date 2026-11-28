package io.quarantaine.math

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

const val PI = 3.1415927f
const val TWO_PI = 6.2831855f

fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v
fun clampi(v: Int, lo: Int, hi: Int) = if (v < lo) lo else if (v > hi) hi else v
fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun smooth(t: Float): Float { val c = clamp(t, 0f, 1f); return c * c * (3f - 2f * c) }

/** Angle ramené dans ]-PI, PI]. */
fun wrapAngle(a: Float): Float {
    var r = a % TWO_PI
    if (r > PI) r -= TWO_PI
    if (r <= -PI) r += TWO_PI
    return r
}

fun len2(x: Float, y: Float) = sqrt(x * x + y * y)
fun len3(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

/** Générateur pseudo-aléatoire rapide et déterministe (xorshift). */
class Rng(seed: Long = 0x2545F4914F6CDD1DL) {
    private var s = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed
    fun nextInt(): Int {
        s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
        return (s ushr 16).toInt()
    }
    fun float(): Float = (nextInt() ushr 8) / 16777216f
    fun range(a: Float, b: Float) = a + (b - a) * float()
    fun int(n: Int): Int = ((nextInt() ushr 1) % n)
    fun chance(p: Float) = float() < p
    fun signed() = float() * 2f - 1f
}

/** Matrices 4x4 en colonne majeure (convention OpenGL). */
object Mat4 {
    fun identity(m: FloatArray) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
    }

    fun perspective(m: FloatArray, fovY: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / tan(fovY / 2f)
        for (i in 0 until 16) m[i] = 0f
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
    }

    fun ortho(m: FloatArray, l: Float, r: Float, b: Float, t: Float, n: Float, f: Float) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 2f / (r - l); m[5] = 2f / (t - b); m[10] = -2f / (f - n)
        m[12] = -(r + l) / (r - l); m[13] = -(t + b) / (t - b); m[14] = -(f + n) / (f - n); m[15] = 1f
    }

    /** Matrice de vue à partir de la position et des vecteurs avant/haut (base orthonormée). */
    fun lookDir(m: FloatArray, ex: Float, ey: Float, ez: Float, fx: Float, fy: Float, fz: Float, ux: Float, uy: Float, uz: Float) {
        // s = f x up
        var sx = fy * uz - fz * uy
        var sy = fz * ux - fx * uz
        var sz = fx * uy - fy * ux
        val sl = len3(sx, sy, sz); sx /= sl; sy /= sl; sz /= sl
        // u = s x f
        val vx = sy * fz - sz * fy
        val vy = sz * fx - sx * fz
        val vz = sx * fy - sy * fx
        m[0] = sx; m[4] = sy; m[8] = sz
        m[1] = vx; m[5] = vy; m[9] = vz
        m[2] = -fx; m[6] = -fy; m[10] = -fz
        m[3] = 0f; m[7] = 0f; m[11] = 0f
        m[12] = -(sx * ex + sy * ey + sz * ez)
        m[13] = -(vx * ex + vy * ey + vz * ez)
        m[14] = fx * ex + fy * ey + fz * ez
        m[15] = 1f
    }

    fun mul(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
            tmp[c * 4 + r] = s
        }
        System.arraycopy(tmp, 0, out, 0, 16)
    }

    private val tmp = FloatArray(16)
}

/**
 * Pile de transformations affines 3x4 pour construire les modèles low-poly sur le CPU.
 * Stockage : m[0..2]=colonne X, m[3..5]=colonne Y, m[6..8]=colonne Z, m[9..11]=translation.
 */
class XfStack(capacity: Int = 32) {
    private val stack = FloatArray(capacity * 12)
    private var top = 0
    val m = FloatArray(12)

    init { reset() }

    fun reset() {
        top = 0
        for (i in 0 until 12) m[i] = 0f
        m[0] = 1f; m[4] = 1f; m[8] = 1f
    }

    fun push() { System.arraycopy(m, 0, stack, top * 12, 12); top++ }
    fun pop() { top--; System.arraycopy(stack, top * 12, m, 0, 12) }

    fun translate(x: Float, y: Float, z: Float) {
        m[9] += m[0] * x + m[3] * y + m[6] * z
        m[10] += m[1] * x + m[4] * y + m[7] * z
        m[11] += m[2] * x + m[5] * y + m[8] * z
    }

    fun scale(x: Float, y: Float, z: Float) {
        for (i in 0..2) { m[i] *= x; m[3 + i] *= y; m[6 + i] *= z }
    }

    /** Rotation autour de Z (lacet). */
    fun rotZ(a: Float) {
        val c = cos(a); val s = sin(a)
        for (i in 0..2) {
            val x = m[i]; val y = m[3 + i]
            m[i] = x * c + y * s
            m[3 + i] = -x * s + y * c
        }
    }

    /** Rotation autour de X local. */
    fun rotX(a: Float) {
        val c = cos(a); val s = sin(a)
        for (i in 0..2) {
            val y = m[3 + i]; val z = m[6 + i]
            m[3 + i] = y * c + z * s
            m[6 + i] = -y * s + z * c
        }
    }

    /** Rotation autour de Y local. */
    fun rotY(a: Float) {
        val c = cos(a); val s = sin(a)
        for (i in 0..2) {
            val x = m[i]; val z = m[6 + i]
            m[i] = x * c - z * s
            m[6 + i] = x * s + z * c
        }
    }

    /** Remplace la matrice courante par une base explicite (colonnes) + origine. */
    fun setBasis(xx: Float, xy: Float, xz: Float, yx: Float, yy: Float, yz: Float, zx: Float, zy: Float, zz: Float, ox: Float, oy: Float, oz: Float) {
        m[0] = xx; m[1] = xy; m[2] = xz
        m[3] = yx; m[4] = yy; m[5] = yz
        m[6] = zx; m[7] = zy; m[8] = zz
        m[9] = ox; m[10] = oy; m[11] = oz
    }

    fun tx(x: Float, y: Float, z: Float) = m[0] * x + m[3] * y + m[6] * z + m[9]
    fun ty(x: Float, y: Float, z: Float) = m[1] * x + m[4] * y + m[7] * z + m[10]
    fun tz(x: Float, y: Float, z: Float) = m[2] * x + m[5] * y + m[8] * z + m[11]
}

fun approxZero(v: Float) = abs(v) < 1e-6f
