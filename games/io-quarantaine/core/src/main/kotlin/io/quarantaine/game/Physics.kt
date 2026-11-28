package io.quarantaine.game

import io.quarantaine.world.Cell
import io.quarantaine.world.Level
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Résultat d'un lancer de rayon contre le décor. */
class Hit {
    var t = 0f
    var kind = NONE
    var nx = 0f; var ny = 0f; var nz = 0f
    var door: io.quarantaine.world.Door? = null

    companion object {
        const val NONE = 0
        const val WALL = 1
        const val FLOOR = 2
        const val CEIL = 3
        const val SKY = 4
    }
}

class Physics(val level: Level) {
    companion object {
        const val STEP = 0.3f
    }

    /** Une cellule bloque-t-elle un corps de pied [z] et de hauteur [h] ? */
    fun cellBlocks(c: Cell?, z: Float, h: Float, flying: Boolean): Boolean {
        if (c == null || c.solid) return true
        val top = level.effCeil(c)
        if (flying) {
            if (c.floor > z - 0.02f) return true
            return top < z + h
        }
        if (c.floor > z + STEP) return true
        return top - max(c.floor, z) < h
    }

    /** Collision cercle/grille. */
    fun blocked(x: Float, y: Float, r: Float, z: Float, h: Float, flying: Boolean): Boolean {
        val x0 = floor(x - r).toInt(); val x1 = floor(x + r).toInt()
        val y0 = floor(y - r).toInt(); val y1 = floor(y + r).toInt()
        for (cy in y0..y1) for (cx in x0..x1) {
            val c = level.cell(cx, cy)
            if (!cellBlocks(c, z, h, flying)) continue
            // Point du carré le plus proche du centre
            val px = x.coerceIn(cx.toFloat(), cx + 1f)
            val py = y.coerceIn(cy.toFloat(), cy + 1f)
            val dx = x - px; val dy = y - py
            if (dx * dx + dy * dy < r * r) return true
        }
        return false
    }

    /** Altitude du sol sous un cercle (plus haute cellule franchissable). */
    fun groundUnder(x: Float, y: Float, r: Float, z: Float): Float {
        val rr = r * 0.7f
        var best = -10f
        val x0 = floor(x - rr).toInt(); val x1 = floor(x + rr).toInt()
        val y0 = floor(y - rr).toInt(); val y1 = floor(y + rr).toInt()
        for (cy in y0..y1) for (cx in x0..x1) {
            val c = level.cell(cx, cy) ?: continue
            if (c.solid) continue
            if (c.floor <= z + STEP && c.floor > best) best = c.floor
        }
        return if (best < -5f) z else best
    }

    /**
     * Déplacement avec glissement le long des murs. Renvoie un masque : 1 = bloqué en X, 2 = bloqué en Y.
     * [out] reçoit la nouvelle position (x, y).
     */
    fun move(x: Float, y: Float, dx: Float, dy: Float, r: Float, z: Float, h: Float, flying: Boolean, out: FloatArray): Int {
        var cx = x; var cy = y
        var mask = 0
        val dist = sqrt(dx * dx + dy * dy)
        val steps = max(1, (dist / 0.15f).toInt() + 1)
        val sx = dx / steps; val sy = dy / steps
        for (i in 0 until steps) {
            if (!blocked(cx + sx, cy, r, z, h, flying)) cx += sx else mask = mask or 1
            if (!blocked(cx, cy + sy, r, z, h, flying)) cy += sy else mask = mask or 2
        }
        out[0] = cx; out[1] = cy
        return mask
    }

    /**
     * Lancer de rayon 3D contre le décor (DDA sur la grille). La direction doit être normalisée.
     */
    fun trace(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxT: Float, hit: Hit): Hit {
        var cx = floor(ox).toInt(); var cy = floor(oy).toInt()
        val stepX = if (dx > 0) 1 else -1
        val stepY = if (dy > 0) 1 else -1
        val tDeltaX = if (abs(dx) < 1e-6f) Float.MAX_VALUE else abs(1f / dx)
        val tDeltaY = if (abs(dy) < 1e-6f) Float.MAX_VALUE else abs(1f / dy)
        var tMaxX = if (abs(dx) < 1e-6f) Float.MAX_VALUE else (if (dx > 0) (cx + 1 - ox) else (ox - cx)) * tDeltaX
        var tMaxY = if (abs(dy) < 1e-6f) Float.MAX_VALUE else (if (dy > 0) (cy + 1 - oy) else (oy - cy)) * tDeltaY
        var tEnter = 0f
        hit.kind = Hit.NONE; hit.t = maxT; hit.door = null
        var cell = level.cell(cx, cy)
        if (cell == null || cell.solid) { hit.kind = Hit.WALL; hit.t = 0f; return hit }
        var guard = 0
        while (guard++ < 256) {
            val tExit = min(min(tMaxX, tMaxY), maxT)
            val c = cell!!
            // Sol / plafond de la cellule courante
            if (dz < -1e-6f) {
                val tf = (c.floor - oz) / dz
                if (tf >= tEnter - 1e-4f && tf <= tExit) { hit.t = max(0f, tf); hit.kind = Hit.FLOOR; hit.nx = 0f; hit.ny = 0f; hit.nz = 1f; return hit }
            } else if (dz > 1e-6f) {
                val top = level.effCeil(c)
                val tc = (top - oz) / dz
                if (tc >= tEnter - 1e-4f && tc <= tExit) {
                    hit.t = max(0f, tc); hit.kind = if (c.sky && c.door == null) Hit.SKY else Hit.CEIL
                    hit.nx = 0f; hit.ny = 0f; hit.nz = -1f; hit.door = c.door; return hit
                }
            }
            if (tExit >= maxT) { hit.t = maxT; hit.kind = Hit.NONE; return hit }
            // Passage à la cellule suivante
            val alongX = tMaxX < tMaxY
            if (alongX) { cx += stepX; tEnter = tMaxX; tMaxX += tDeltaX } else { cy += stepY; tEnter = tMaxY; tMaxY += tDeltaY }
            val n = level.cell(cx, cy)
            val zz = oz + dz * tEnter
            val blocks = n == null || n.solid || zz < n.floor - 1e-3f || zz > level.effCeil(n) + 1e-3f
            if (blocks) {
                // Au-dessus d'une cellule à ciel ouvert : le rayon file vers le ciel
                if (c.sky && zz > Level.SKY_WALL) { hit.kind = Hit.SKY; hit.t = tEnter; return hit }
                hit.t = tEnter; hit.kind = Hit.WALL
                hit.nx = if (alongX) -stepX.toFloat() else 0f
                hit.ny = if (alongX) 0f else -stepY.toFloat()
                hit.nz = 0f
                hit.door = n?.door
                return hit
            }
            cell = n
        }
        hit.kind = Hit.NONE; hit.t = maxT
        return hit
    }

    private val losHit = Hit()

    /** Ligne de vue entre deux points. */
    fun lineOfSight(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Boolean {
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1e-4f) return true
        trace(x0, y0, z0, dx / d, dy / d, dz / d, d, losHit)
        return losHit.kind == Hit.NONE
    }

    /** Intersection rayon / cylindre vertical. Renvoie t ou -1. */
    fun rayCylinder(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, cx: Float, cy: Float, cz: Float, r: Float, h: Float): Float {
        val a = dx * dx + dy * dy
        if (a < 1e-8f) return -1f
        val fx = ox - cx; val fy = oy - cy
        val b = 2f * (dx * fx + dy * fy)
        val c = fx * fx + fy * fy - r * r
        val disc = b * b - 4f * a * c
        if (disc < 0f) return -1f
        val s = sqrt(disc)
        var t = (-b - s) / (2f * a)
        val t2 = (-b + s) / (2f * a)
        if (t2 < 0f) return -1f
        if (t < 0f) t = 0f
        val z = oz + dz * t
        if (z >= cz && z <= cz + h) return t
        // Entrée possible par le dessus ou le dessous
        if (abs(dz) > 1e-6f) {
            val tp0 = (cz - oz) / dz
            if (tp0 >= t && tp0 <= t2) return tp0
            val tp1 = (cz + h - oz) / dz
            if (tp1 >= t && tp1 <= t2) return tp1
        }
        return -1f
    }
}
