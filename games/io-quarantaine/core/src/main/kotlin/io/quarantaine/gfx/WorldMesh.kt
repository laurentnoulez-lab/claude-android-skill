package io.quarantaine.gfx

import io.quarantaine.world.Cell
import io.quarantaine.world.Level
import io.quarantaine.world.Light
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Lot de géométrie statique pour une texture (≤ 65 000 sommets pour des index 16 bits). */
class MeshChunk(val tex: Int, val verts: FloatArray, val indices: ShortArray) {
    val vertexCount get() = verts.size / WorldMesh.STRIDE
}

/**
 * Construit le maillage 3D d'un niveau (sols, plafonds, murs, contremarches) avec un éclairage
 * pré-calculé par sommet (lumières statiques + ombres portées sur la grille + occlusion d'angle).
 */
class WorldMesh(private val level: Level) {
    companion object {
        const val STRIDE = 8
        private const val MAX_VERTS = 60000
    }

    private class Builder(val tex: Int) {
        val v = FloatList(4096)
        val i = ShortList(4096)
        var count = 0
        val done = ArrayList<MeshChunk>()

        fun flushIfFull(extra: Int) {
            if (count + extra > MAX_VERTS) flush()
        }

        fun flush() {
            if (count == 0) return
            done.add(MeshChunk(tex, v.data.copyOf(v.size), i.data.copyOf(i.size)))
            v.clear(); i.size = 0; count = 0
        }
    }

    private val builders = HashMap<Int, Builder>()
    private val lightGrid: Array<IntArray>
    private val col = FloatArray(3)

    init {
        // Index spatial : lumières touchant chaque cellule
        val lists = Array(level.w * level.h) { ArrayList<Int>() }
        level.lights.forEachIndexed { li, l ->
            val x0 = max(0, floor(l.x - l.radius).toInt()); val x1 = min(level.w - 1, floor(l.x + l.radius).toInt())
            val y0 = max(0, floor(l.y - l.radius).toInt()); val y1 = min(level.h - 1, floor(l.y + l.radius).toInt())
            for (cy in y0..y1) for (cx in x0..x1) lists[cy * level.w + cx].add(li)
        }
        lightGrid = Array(lists.size) { lists[it].toIntArray() }
    }

    fun build(): List<MeshChunk> {
        for (cy in 0 until level.h) for (cx in 0 until level.w) {
            val c = level.cell(cx, cy)!!
            if (c.solid) continue
            floorAndCeiling(cx, cy, c)
            for ((dx, dy) in Level.NEIGH4) walls(cx, cy, c, dx, dy)
        }
        bakeCellLights()
        val out = ArrayList<MeshChunk>()
        for (b in builders.values) { b.flush(); out.addAll(b.done) }
        return out
    }

    private fun builder(tex: Int) = builders.getOrPut(tex) { Builder(tex) }

    private fun ambient(c: Cell): FloatArray = if (c.sky) level.def.skyAmbient else level.def.ambient

    /** Éclairage d'un point avec normale. */
    fun lightAt(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, amb: FloatArray, out: FloatArray) {
        out[0] = amb[0]; out[1] = amb[1]; out[2] = amb[2]
        // On échantillonne côté visible de la surface
        val sx = (x + nx * 0.05f); val sy = (y + ny * 0.05f)
        val list = lightGrid[floor(sy).toInt().coerceIn(0, level.h - 1) * level.w + floor(sx).toInt().coerceIn(0, level.w - 1)]
        for (li in list) {
            val l = level.lights[li]
            val dx = l.x - x; val dy = l.y - y; val dz = l.z - z
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 >= l.radius * l.radius) continue
            val d = sqrt(d2).coerceAtLeast(0.001f)
            val ndl = (dx * nx + dy * ny + dz * nz) / d
            if (ndl < -0.2f) continue
            val wrap = (ndl * 0.65f + 0.35f).coerceAtLeast(0f)
            val att = (1f - d / l.radius).let { it * it }
            if (d > 0.7f && !visible(l, sx, sy)) continue
            val k = att * wrap * 1.25f
            out[0] += l.r * k; out[1] += l.g * k; out[2] += l.b * k
        }
    }

    /** Ombres portées en 2D : les murs pleins bloquent la lumière. */
    private fun visible(l: Light, x: Float, y: Float): Boolean {
        val dx = x - l.x; val dy = y - l.y
        val d = sqrt(dx * dx + dy * dy)
        if (d < 1e-3f) return true
        val steps = (d / 0.25f).toInt() + 1
        val sx = dx / steps; val sy = dy / steps
        var px = l.x; var py = l.y
        val lcx = floor(l.x).toInt(); val lcy = floor(l.y).toInt()
        for (i in 1 until steps) {
            px += sx; py += sy
            val cx = floor(px).toInt(); val cy = floor(py).toInt()
            if (cx == lcx && cy == lcy) continue
            val c = level.cell(cx, cy) ?: return false
            if (c.solid) return false
        }
        return true
    }

    private fun vtx(b: Builder, x: Float, y: Float, z: Float, u: Float, v: Float, nx: Float, ny: Float, nz: Float, amb: FloatArray, ao: Float) {
        lightAt(x, y, z, nx, ny, nz, amb, col)
        b.v.ensure(STRIDE)
        val d = b.v.data; var s = b.v.size
        d[s++] = x; d[s++] = y; d[s++] = z; d[s++] = u; d[s++] = v
        d[s++] = col[0] * ao; d[s++] = col[1] * ao; d[s++] = col[2] * ao
        b.v.size = s
        b.count++
    }

    private fun quadIdx(b: Builder) {
        val base = b.count - 4
        b.i.add(base); b.i.add(base + 1); b.i.add(base + 2)
        b.i.add(base); b.i.add(base + 2); b.i.add(base + 3)
    }

    private fun floorAndCeiling(cx: Int, cy: Int, c: Cell) {
        val amb = ambient(c)
        val fb = builder(c.floorTex)
        val z = c.floor
        for (sy in 0..1) for (sx in 0..1) {
            fb.flushIfFull(4)
            val x0 = cx + sx * 0.5f; val y0 = cy + sy * 0.5f; val x1 = x0 + 0.5f; val y1 = y0 + 0.5f
            vtx(fb, x0, y0, z, x0, -y0, 0f, 0f, 1f, amb, aoFloor(x0, y0, c))
            vtx(fb, x1, y0, z, x1, -y0, 0f, 0f, 1f, amb, aoFloor(x1, y0, c))
            vtx(fb, x1, y1, z, x1, -y1, 0f, 0f, 1f, amb, aoFloor(x1, y1, c))
            vtx(fb, x0, y1, z, x0, -y1, 0f, 0f, 1f, amb, aoFloor(x0, y1, c))
            quadIdx(fb)
        }
        if (c.sky) return
        val cb = builder(c.ceilTex)
        val zc = c.ceil
        if (c.ceilTex == Tex.CEIL_LIGHT) {
            cb.flushIfFull(4)
            val x0 = cx.toFloat(); val y0 = cy.toFloat(); val x1 = x0 + 1f; val y1 = y0 + 1f
            vtx(cb, x0, y0, zc, 0f, 0f, 0f, 0f, -1f, amb, 1f)
            vtx(cb, x0, y1, zc, 0f, 1f, 0f, 0f, -1f, amb, 1f)
            vtx(cb, x1, y1, zc, 1f, 1f, 0f, 0f, -1f, amb, 1f)
            vtx(cb, x1, y0, zc, 1f, 0f, 0f, 0f, -1f, amb, 1f)
            quadIdx(cb)
            return
        }
        for (sy in 0..1) for (sx in 0..1) {
            cb.flushIfFull(4)
            val x0 = cx + sx * 0.5f; val y0 = cy + sy * 0.5f; val x1 = x0 + 0.5f; val y1 = y0 + 0.5f
            vtx(cb, x0, y0, zc, x0, y0, 0f, 0f, -1f, amb, 1f)
            vtx(cb, x0, y1, zc, x0, y1, 0f, 0f, -1f, amb, 1f)
            vtx(cb, x1, y1, zc, x1, y1, 0f, 0f, -1f, amb, 1f)
            vtx(cb, x1, y0, zc, x1, y0, 0f, 0f, -1f, amb, 1f)
            quadIdx(cb)
        }
    }

    private fun aoFloor(x: Float, y: Float, c: Cell): Float {
        if (c.lava) return 1f
        var solid = 0
        for (oy in intArrayOf(-1, 0)) for (ox in intArrayOf(-1, 0)) {
            val n = level.cell(floor(x + ox * 0.5f).toInt(), floor(y + oy * 0.5f).toInt())
            if (n == null || n.solid || n.floor > c.floor + 0.3f) solid++
        }
        return 1f - 0.13f * solid
    }

    private fun walls(cx: Int, cy: Int, c: Cell, dx: Int, dy: Int) {
        val n = level.cell(cx + dx, cy + dy)
        val topC = if (c.sky) Level.SKY_WALL + c.floor.coerceAtLeast(0f) else c.ceil
        if (n == null || n.solid) {
            face(cx, cy, dx, dy, c.floor, topC, n?.wallTex ?: Tex.TECH, c, n?.exit == true)
            return
        }
        if (n.floor > c.floor + 0.001f) face(cx, cy, dx, dy, c.floor, min(n.floor, topC), n.stepTex, c, false)
        if (c.sky && !n.sky) {
            if (n.ceil < topC) face(cx, cy, dx, dy, n.ceil, topC, frameTex(cx + dx, cy + dy, dx, dy), c, false)
        } else if (!c.sky && !n.sky && n.ceil < c.ceil - 0.001f) {
            face(cx, cy, dx, dy, max(n.ceil, c.floor), c.ceil, frameTex(cx + dx, cy + dy, dx, dy), c, false)
        }
    }

    /** Texture du linteau : celle des murs qui encadrent la cellule voisine. */
    private fun frameTex(nx: Int, ny: Int, dx: Int, dy: Int): Int {
        val a = level.cell(nx + dy, ny + dx); val b = level.cell(nx - dy, ny - dx)
        if (a != null && a.solid && !a.exit) return a.wallTex
        if (b != null && b.solid && !b.exit) return b.wallTex
        return Tex.TECH
    }

    /** Face verticale sur la frontière (cx,cy)->(cx+dx,cy+dy), orientée vers la cellule (cx,cy). */
    private fun face(cx: Int, cy: Int, dx: Int, dy: Int, bottom: Float, top: Float, tex: Int, c: Cell, stretch: Boolean) {
        if (top - bottom < 0.001f) return
        val b = builder(tex)
        val amb = ambient(c)
        // Normale vers l'intérieur de la cellule, direction de vue = -normale
        val nx = -dx.toFloat(); val ny = -dy.toFloat()
        // Vecteur « droite » pour un observateur regardant le mur : (fy, -fx) avec f = (dx, dy)
        val rx = dy.toFloat(); val ry = -dx.toFloat()
        val mx = cx + 0.5f + dx * 0.5f; val my = cy + 0.5f + dy * 0.5f
        val lx = mx - rx * 0.5f; val ly = my - ry * 0.5f
        val qx = mx + rx * 0.5f; val qy = my + ry * 0.5f
        val ul = lx * rx + ly * ry; val ur = qx * rx + qy * ry
        val segs = max(1, ((top - bottom) / 0.5f + 0.5f).toInt())
        for (s in 0 until segs) {
            val z0 = bottom + (top - bottom) * s / segs
            val z1 = bottom + (top - bottom) * (s + 1) / segs
            val v0 = if (stretch) (top - z0) / (top - bottom) else top - z0
            val v1 = if (stretch) (top - z1) / (top - bottom) else top - z1
            val u0 = if (stretch) 0f else ul; val u1 = if (stretch) 1f else ur
            b.flushIfFull(4)
            vtx(b, lx, ly, z0, u0, v0, nx, ny, 0f, amb, if (s == 0) 0.82f else 1f)
            vtx(b, qx, qy, z0, u1, v0, nx, ny, 0f, amb, if (s == 0) 0.82f else 1f)
            vtx(b, qx, qy, z1, u1, v1, nx, ny, 0f, amb, 1f)
            vtx(b, lx, ly, z1, u0, v1, nx, ny, 0f, amb, 1f)
            quadIdx(b)
        }
    }

    /** Lumière au centre de chaque cellule, pour éclairer ennemis et objets. */
    private fun bakeCellLights() {
        for (cy in 0 until level.h) for (cx in 0 until level.w) {
            val c = level.cell(cx, cy)!!
            if (c.solid) continue
            lightAt(cx + 0.5f, cy + 0.5f, c.floor + 0.5f, 0f, 0f, 1f, ambient(c), col)
            // Moyenne avec une normale latérale pour éviter la sur-exposition par le plafonnier
            val up0 = col[0]; val up1 = col[1]; val up2 = col[2]
            lightAt(cx + 0.5f, cy + 0.5f, c.floor + 0.5f, 0.7f, 0.7f, 0f, ambient(c), col)
            c.lr = (up0 + col[0]) * 0.5f; c.lg = (up1 + col[1]) * 0.5f; c.lb = (up2 + col[2]) * 0.5f
        }
        // Les portes prennent la lumière de la cellule voisine la plus éclairée
        for (d in level.doors) {
            val c = level.cell(d.cx, d.cy)!!
            for ((dx, dy) in Level.NEIGH4) {
                val n = level.cell(d.cx + dx, d.cy + dy) ?: continue
                if (n.solid || n.door != null) continue
                if (n.lr + n.lg + n.lb > c.lr + c.lg + c.lb) { c.lr = n.lr; c.lg = n.lg; c.lb = n.lb }
            }
        }
    }
}
