package io.quarantaine.gfx

import io.quarantaine.math.XfStack
import io.quarantaine.platform.GL
import io.quarantaine.platform.Gl
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Tampon de sommets GL réutilisé à chaque image (flux). */
class StreamBuffer(private val gl: Gl) {
    private val vbo = gl.genBuffer()
    private var fb: FloatBuffer = GlUtil.floatBuffer(1 shl 16)

    fun upload(data: FloatArray, count: Int) {
        if (fb.capacity() < count) fb = GlUtil.floatBuffer(Integer.highestOneBit(count) shl 1)
        fb.clear(); fb.put(data, 0, count); fb.flip()
        gl.bindBuffer(GL.ARRAY_BUFFER, vbo)
        gl.bufferData(GL.ARRAY_BUFFER, fb, GL.STREAM_DRAW)
    }
}

/**
 * Géométrie dynamique (ennemis, objets, portes, arme) transformée sur le CPU puis envoyée en un
 * appel de dessin par texture. Format identique au décor : position, uv, couleur (8 flottants).
 */
class DynBatch {
    private companion object {
        val OX = floatArrayOf(1f, 0f, -1f, 0f)
        val OY = floatArrayOf(0f, 1f, 0f, -1f)
    }
    val lists = Array(Tex.COUNT) { FloatList(2048) }
    val xf = XfStack()
    // Lumière ambiante courante appliquée aux faces non émissives
    var lr = 1f; var lg = 1f; var lb = 1f
    /** Sans ombrage directionnel (portes : même rendu que les murs statiques). */
    var flat = false

    fun clear() { for (l in lists) l.clear(); xf.reset() }

    fun setLight(r: Float, g: Float, b: Float) { lr = r; lg = g; lb = b }

    private fun shade(nx: Float, ny: Float, nz: Float): Float {
        val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
        val d = (nx * 0.35f + ny * 0.25f + nz * 0.9f) / l
        return 0.62f + 0.38f * max(-0.4f, d)
    }

    private fun put(list: FloatList, x: Float, y: Float, z: Float, u: Float, v: Float, r: Float, g: Float, b: Float) {
        list.ensure(8)
        val d = list.data; var s = list.size
        d[s++] = x; d[s++] = y; d[s++] = z; d[s++] = u; d[s++] = v; d[s++] = r; d[s++] = g; d[s++] = b
        list.size = s
    }

    /** Triangle en coordonnées locales (transformé par la pile courante). */
    fun tri(
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float, tex: Int = Tex.WHITE, emissive: Boolean = false,
        u0: Float = 0f, v0: Float = 0f, u1: Float = 1f, v1: Float = 0f, u2: Float = 1f, v2: Float = 1f,
    ) {
        val m = xf
        val ax = m.tx(x0, y0, z0); val ay = m.ty(x0, y0, z0); val az = m.tz(x0, y0, z0)
        val bx = m.tx(x1, y1, z1); val by = m.ty(x1, y1, z1); val bz = m.tz(x1, y1, z1)
        val cx = m.tx(x2, y2, z2); val cy = m.ty(x2, y2, z2); val cz = m.tz(x2, y2, z2)
        val ex = bx - ax; val ey = by - ay; val ez = bz - az
        val fx = cx - ax; val fy = cy - ay; val fz = cz - az
        val nx = ey * fz - ez * fy; val ny = ez * fx - ex * fz; val nz = ex * fy - ey * fx
        val k = if (emissive || flat) 1f else shade(nx, ny, nz)
        val cr = if (emissive) r else r * lr * k
        val cg = if (emissive) g else g * lg * k
        val cb = if (emissive) b else b * lb * k
        val l = lists[tex]
        put(l, ax, ay, az, u0, v0, cr, cg, cb)
        put(l, bx, by, bz, u1, v1, cr, cg, cb)
        put(l, cx, cy, cz, u2, v2, cr, cg, cb)
    }

    fun quad(
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float,
        r: Float, g: Float, b: Float, tex: Int = Tex.WHITE, emissive: Boolean = false,
        us: Float = 1f, vs: Float = 1f, uo: Float = 0f, vo: Float = 0f,
    ) {
        tri(x0, y0, z0, x1, y1, z1, x2, y2, z2, r, g, b, tex, emissive, uo, vo + vs, uo + us, vo + vs, uo + us, vo)
        tri(x0, y0, z0, x2, y2, z2, x3, y3, z3, r, g, b, tex, emissive, uo, vo + vs, uo + us, vo, uo, vo)
    }

    /** Pavé centré en (cx,cy,cz) de dimensions (sx,sy,sz) dans le repère courant. */
    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float, tex: Int = Tex.WHITE, emissive: Boolean = false, texScale: Float = 1f) {
        val x0 = cx - sx / 2; val x1 = cx + sx / 2
        val y0 = cy - sy / 2; val y1 = cy + sy / 2
        val z0 = cz - sz / 2; val z1 = cz + sz / 2
        val us = if (tex == Tex.WHITE) 1f else texScale
        // dessus, dessous, 4 côtés
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, tex, emissive, sx * us, sy * us)
        quad(x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0, r, g, b, tex, emissive, sx * us, sy * us)
        quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, tex, emissive, sx * us, sz * us)
        quad(x1, y1, z0, x0, y1, z0, x0, y1, z1, x1, y1, z1, r, g, b, tex, emissive, sx * us, sz * us)
        quad(x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1, r, g, b, tex, emissive, sy * us, sz * us)
        quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, tex, emissive, sy * us, sz * us)
    }

    /** Octaèdre (cristaux, projectiles, yeux). */
    fun octa(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, r: Float, g: Float, b: Float, emissive: Boolean = false) {
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val pxi = OX[i] * rx; val pyi = OY[i] * ry; val pxj = OX[j] * rx; val pyj = OY[j] * ry
            tri(cx + pxi, cy + pyi, cz, cx + pxj, cy + pyj, cz, cx, cy, cz + rz, r, g, b, Tex.WHITE, emissive)
            tri(cx + pxj, cy + pyj, cz, cx + pxi, cy + pyi, cz, cx, cy, cz - rz, r * 0.8f, g * 0.8f, b * 0.8f, Tex.WHITE, emissive)
        }
    }

    /** Prisme à n côtés (cylindre low-poly) d'axe Z. */
    fun cyl(cx: Float, cy: Float, z0: Float, z1: Float, rad: Float, n: Int, r: Float, g: Float, b: Float, tex: Int = Tex.WHITE, emissive: Boolean = false, rTop: Float = rad) {
        for (i in 0 until n) {
            val a0 = i * 6.2831855f / n; val a1 = (i + 1) * 6.2831855f / n
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            quad(cx + c0 * rad, cy + s0 * rad, z0, cx + c1 * rad, cy + s1 * rad, z0,
                cx + c1 * rTop, cy + s1 * rTop, z1, cx + c0 * rTop, cy + s0 * rTop, z1, r, g, b, tex, emissive, 1f / n * 3f, 1f, i * 3f / n)
            tri(cx, cy, z1, cx + c0 * rTop, cy + s0 * rTop, z1, cx + c1 * rTop, cy + s1 * rTop, z1, r, g, b, tex, emissive)
            tri(cx, cy, z0, cx + c1 * rad, cy + s1 * rad, z0, cx + c0 * rad, cy + s0 * rad, z0, r * 0.7f, g * 0.7f, b * 0.7f, tex, emissive)
        }
    }

    /** Cylindre d'axe X (canons d'armes). */
    fun cylX(x0: Float, x1: Float, cy: Float, cz: Float, rad: Float, n: Int, r: Float, g: Float, b: Float, emissive: Boolean = false, rEnd: Float = rad) {
        xf.push()
        xf.translate(x0, cy, cz)
        xf.rotY(1.5707964f)
        cyl(0f, 0f, 0f, x1 - x0, rad, n, r, g, b, Tex.WHITE, emissive, rEnd)
        xf.pop()
    }

    fun draw(gl: Gl, stream: StreamBuffer, textures: IntArray) {
        for (t in lists.indices) {
            val l = lists[t]
            if (l.size == 0) continue
            stream.upload(l.data, l.size)
            gl.bindTexture(textures[t])
            gl.vertexAttribPointer(0, 3, 32, 0)
            gl.vertexAttribPointer(1, 2, 32, 12)
            gl.vertexAttribPointer(2, 3, 32, 20)
            gl.drawArrays(GL.TRIANGLES, 0, l.size / 8)
        }
    }
}

/** Particules lumineuses additives (étincelles, flammes) en panneaux face caméra. */
class FxBatch {
    val list = FloatList(8192)
    // Vecteurs droite/haut de la caméra
    var rx = 1f; var ry = 0f; var rz = 0f
    var ux = 0f; var uy = 0f; var uz = 1f

    fun clear() = list.clear()

    fun sprite(x: Float, y: Float, z: Float, size: Float, r: Float, g: Float, b: Float, a: Float) {
        val ax = (rx + ux) * size; val ay = (ry + uy) * size; val az = (rz + uz) * size
        val bx = (rx - ux) * size; val by = (ry - uy) * size; val bz = (rz - uz) * size
        v(x - ax, y - ay, z - az, 0f, 1f, r, g, b, a)
        v(x + bx, y + by, z + bz, 1f, 1f, r, g, b, a)
        v(x + ax, y + ay, z + az, 1f, 0f, r, g, b, a)
        v(x - ax, y - ay, z - az, 0f, 1f, r, g, b, a)
        v(x + ax, y + ay, z + az, 1f, 0f, r, g, b, a)
        v(x - bx, y - by, z - bz, 0f, 0f, r, g, b, a)
    }

    private fun v(x: Float, y: Float, z: Float, u: Float, vv: Float, r: Float, g: Float, b: Float, a: Float) {
        list.ensure(9)
        val d = list.data; var s = list.size
        d[s++] = x; d[s++] = y; d[s++] = z; d[s++] = u; d[s++] = vv; d[s++] = r; d[s++] = g; d[s++] = b; d[s++] = a
        list.size = s
    }

    fun draw(gl: Gl, stream: StreamBuffer) {
        if (list.size == 0) return
        stream.upload(list.data, list.size)
        gl.vertexAttribPointer(0, 3, 36, 0)
        gl.vertexAttribPointer(1, 2, 36, 12)
        gl.vertexAttribPointer(2, 4, 36, 20)
        gl.drawArrays(GL.TRIANGLES, 0, list.size / 9)
    }
}

/** Interface 2D : rectangles texturés en pixels écran. */
class HudBatch(private val gl: Gl, private val stream: StreamBuffer) {
    private val list = FloatList(16384)
    private var tex = -1

    fun begin() { list.clear(); tex = -1 }

    fun texture(t: Int) {
        if (t != tex) { flush(); tex = t; gl.bindTexture(t) }
    }

    fun rect(x0: Float, y0: Float, x1: Float, y1: Float, u0: Float, v0: Float, u1: Float, v1: Float, r: Float, g: Float, b: Float, a: Float) {
        v(x0, y0, u0, v0, r, g, b, a); v(x1, y0, u1, v0, r, g, b, a); v(x1, y1, u1, v1, r, g, b, a)
        v(x0, y0, u0, v0, r, g, b, a); v(x1, y1, u1, v1, r, g, b, a); v(x0, y1, u0, v1, r, g, b, a)
    }

    /** Quadrilatère quelconque (rotation des flèches de l'automap). */
    fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, u0: Float, v0: Float, u1: Float, v1: Float, r: Float, g: Float, b: Float, a: Float) {
        v(x0, y0, u0, v0, r, g, b, a); v(x1, y1, u1, v0, r, g, b, a); v(x2, y2, u1, v1, r, g, b, a)
        v(x0, y0, u0, v0, r, g, b, a); v(x2, y2, u1, v1, r, g, b, a); v(x3, y3, u0, v1, r, g, b, a)
    }

    private fun v(x: Float, y: Float, u: Float, vv: Float, r: Float, g: Float, b: Float, a: Float) {
        list.ensure(8)
        val d = list.data; var s = list.size
        d[s++] = x; d[s++] = y; d[s++] = u; d[s++] = vv; d[s++] = r; d[s++] = g; d[s++] = b; d[s++] = a
        list.size = s
    }

    fun flush() {
        if (list.size == 0) return
        stream.upload(list.data, list.size)
        gl.vertexAttribPointer(0, 2, 32, 0)
        gl.vertexAttribPointer(1, 2, 32, 8)
        gl.vertexAttribPointer(2, 4, 32, 16)
        gl.drawArrays(GL.TRIANGLES, 0, list.size / 8)
        list.clear()
    }
}
