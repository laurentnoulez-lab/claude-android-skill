package io.quarantaine.gfx

import io.quarantaine.game.Enemies
import io.quarantaine.game.Enemy
import io.quarantaine.game.Pickup
import io.quarantaine.game.Player
import io.quarantaine.game.Projectile
import io.quarantaine.game.Weapons
import io.quarantaine.world.Door
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Modèles low-poly construits par code (repère local : x = avant, y = gauche, z = haut). */
object Models {
    private val SIDES = intArrayOf(-1, 1)
    private val FLASH_GREEN = floatArrayOf(0.5f, 1f, 0.6f)
    private val FLASH_YELLOW = floatArrayOf(1f, 0.85f, 0.4f)

    // ------------------------------------------------------------------ ennemis

    fun enemy(b: DynBatch, e: Enemy, time: Float, lr: Float, lg: Float, lb: Float) {
        val flash = if (e.hitFlash > 0f) 2.5f else 1f
        b.setLight(lr * flash, lg * flash, lb * flash)
        val m = b.xf
        m.reset()
        m.translate(e.x, e.y, e.z)
        m.rotZ(e.yaw)
        if (!e.alive && e.type.id != Enemies.BARREL) {
            val f = min(1f, e.deathTime * 3.2f)
            if (e.type.id == Enemies.DRONE) {
                // Le drone tombe au sol puis reste en épave
                m.translate(0f, 0f, -min(e.z - floorOf(e), e.deathTime * e.deathTime * 6f))
                m.rotX(f * 0.9f)
            } else if (e.type.id == Enemies.CRAWLER) {
                m.rotX(f * 3.14f)
                m.translate(0f, 0f, -0.3f * f)
            } else if (e.type.id == Enemies.BOSS) {
                m.translate(0f, 0f, -f * 0.9f)
                m.rotY(-f * 0.5f)
            } else {
                m.rotY(-f * 1.45f)
                m.translate(0f, 0f, f * e.type.radius * 0.4f)
            }
        }
        val walk = sin(e.walkPhase)
        val attacking = e.state == Enemy.WINDUP
        when (e.type.id) {
            Enemies.MINER -> miner(b, walk, attacking, e.alive)
            Enemies.CRAWLER -> crawler(b, e.walkPhase, attacking, time)
            Enemies.SPITTER -> spitter(b, walk, attacking, time)
            Enemies.BRUTE -> brute(b, walk, attacking || e.state == Enemy.CHARGE, time)
            Enemies.DRONE -> drone(b, time + e.x, e.alive, attacking)
            Enemies.BOSS -> boss(b, walk, attacking, time, e)
            Enemies.BARREL -> barrel(b, e.alive)
        }
    }

    private fun floorOf(e: Enemy) = e.z - 0.9f

    private fun miner(b: DynBatch, walk: Float, attack: Boolean, alive: Boolean) {
        val m = b.xf
        val suitR = 0.85f; val suitG = 0.42f; val suitB = 0.1f
        // Jambes
        for (side in SIDES) {
            m.push(); m.translate(0f, side * 0.065f, 0.27f); m.rotY(walk * 0.55f * side)
            b.box(0f, 0f, -0.13f, 0.1f, 0.09f, 0.27f, suitR * 0.8f, suitG * 0.8f, suitB, Tex.METAL)
            b.box(0.02f, 0f, -0.25f, 0.13f, 0.1f, 0.05f, 0.2f, 0.18f, 0.16f)
            m.pop()
        }
        // Torse et sac
        b.box(0f, 0f, 0.39f, 0.16f, 0.27f, 0.25f, suitR, suitG, suitB, Tex.METAL)
        b.box(-0.11f, 0f, 0.41f, 0.08f, 0.2f, 0.2f, 0.3f, 0.32f, 0.3f)
        b.box(0.085f, 0.07f, 0.45f, 0.02f, 0.05f, 0.03f, 0.3f, 1f, 0.4f, emissive = true)
        // Excroissances infectées
        b.octa(0.0f, 0.13f, 0.5f, 0.08f, 0.07f, 0.07f, 0.6f, 0.1f, 0.2f)
        b.octa(-0.06f, -0.1f, 0.33f, 0.05f, 0.05f, 0.06f, 0.55f, 0.1f, 0.18f)
        // Tête (casque fendu)
        b.box(0.0f, 0f, 0.58f, 0.14f, 0.14f, 0.13f, 0.45f, 0.5f, 0.4f, Tex.SKIN)
        b.box(0.03f, 0f, 0.645f, 0.16f, 0.16f, 0.03f, 0.9f, 0.75f, 0.15f)
        b.box(0.072f, 0.035f, 0.585f, 0.01f, 0.03f, 0.02f, 1f, 0.15f, 0.1f, emissive = true)
        b.box(0.072f, -0.035f, 0.585f, 0.01f, 0.03f, 0.02f, 1f, 0.15f, 0.1f, emissive = true)
        // Bras et fusil
        m.push(); m.translate(0.02f, 0f, 0.47f); m.rotY(if (attack) -0.15f else 0.25f)
        b.box(0.1f, 0.12f, -0.02f, 0.2f, 0.06f, 0.06f, suitR * 0.9f, suitG * 0.9f, suitB)
        b.box(0.1f, -0.12f, -0.02f, 0.2f, 0.06f, 0.06f, suitR * 0.9f, suitG * 0.9f, suitB)
        b.box(0.2f, -0.03f, 0.0f, 0.34f, 0.05f, 0.06f, 0.14f, 0.14f, 0.15f, Tex.METAL)
        b.box(0.08f, -0.03f, -0.05f, 0.05f, 0.04f, 0.1f, 0.14f, 0.14f, 0.15f)
        m.pop()
        if (!alive) b.octa(0f, 0f, 0.62f, 0.04f, 0.04f, 0.04f, 0.6f, 0.1f, 0.15f)
    }

    private fun crawler(b: DynBatch, phase: Float, attack: Boolean, time: Float) {
        val m = b.xf
        val r = 0.55f; val g = 0.16f; val bl = 0.32f
        b.box(-0.04f, 0f, 0.17f, 0.36f, 0.28f, 0.15f, r, g, bl, Tex.SKIN, texScale = 3f)
        b.box(-0.22f, 0f, 0.2f, 0.16f, 0.22f, 0.14f, r * 0.8f, g, bl * 0.9f, Tex.SKIN, texScale = 3f)
        for (i in 0 until 3) b.octa(-0.12f + i * 0.1f, 0f, 0.27f, 0.04f, 0.04f, 0.07f, 0.85f, 0.75f, 0.6f)
        // Tête et mandibules
        val bite = if (attack) sin(time * 30f) * 0.25f else 0f
        m.push(); m.translate(0.2f, 0f, 0.17f)
        b.box(0f, 0f, 0f, 0.14f, 0.18f, 0.11f, r * 0.9f, g * 0.9f, bl)
        for (side in SIDES) {
            m.push(); m.translate(0.07f, side * 0.06f, -0.02f); m.rotZ(side * (0.3f + bite))
            b.box(0.05f, 0f, 0f, 0.1f, 0.025f, 0.025f, 0.9f, 0.85f, 0.7f)
            m.pop()
            b.box(0.072f, side * 0.04f, 0.035f, 0.01f, 0.025f, 0.02f, 1f, 0.9f, 0.1f, emissive = true)
            b.box(0.072f, side * 0.075f, 0.02f, 0.01f, 0.015f, 0.015f, 1f, 0.9f, 0.1f, emissive = true)
        }
        m.pop()
        // 6 pattes
        for (i in 0 until 3) for (side in SIDES) {
            val sw = sin(phase * 2f + i * 2.1f + if (side > 0) 3.14f else 0f) * 0.35f
            m.push(); m.translate(0.1f - i * 0.12f, side * 0.13f, 0.18f); m.rotZ(side * (1.2f) + sw)
            m.rotX(side * 0.6f)
            b.box(0.13f, 0f, 0f, 0.26f, 0.03f, 0.03f, r * 0.7f, g * 0.7f, bl * 0.7f)
            m.translate(0.26f, 0f, 0f); m.rotY(1.1f)
            b.box(0.09f, 0f, 0f, 0.18f, 0.025f, 0.025f, 0.2f, 0.08f, 0.12f)
            m.pop()
        }
    }

    private fun spitter(b: DynBatch, walk: Float, attack: Boolean, time: Float) {
        val m = b.xf
        val r = 0.42f; val g = 0.55f; val bl = 0.3f
        for (side in SIDES) {
            m.push(); m.translate(0f, side * 0.1f, 0.22f); m.rotY(walk * 0.4f * side)
            b.box(0f, 0f, -0.11f, 0.12f, 0.11f, 0.22f, r * 0.8f, g * 0.8f, bl * 0.8f, Tex.SKIN)
            m.pop()
        }
        b.octa(0f, 0f, 0.4f, 0.2f, 0.22f, 0.2f, r, g, bl)
        b.box(0f, 0f, 0.38f, 0.22f, 0.26f, 0.2f, r, g, bl, Tex.SKIN)
        // Poche d'acide dorsale pulsante
        val pulse = 0.75f + 0.25f * sin(time * 4f) + if (attack) 0.4f else 0f
        b.octa(-0.17f, 0f, 0.47f, 0.13f, 0.15f, 0.16f, 0.35f * pulse, 0.9f * pulse, 0.15f * pulse, emissive = true)
        // Tête et gueule
        m.push(); m.translate(0.1f, 0f, 0.56f); m.rotY(if (attack) -0.4f else 0f)
        b.box(0f, 0f, 0f, 0.16f, 0.16f, 0.12f, r * 0.9f, g * 0.9f, bl, Tex.SKIN)
        b.box(0.08f, 0f, -0.025f, 0.02f, 0.1f, if (attack) 0.07f else 0.03f, 0.3f, 0.9f, 0.2f, emissive = true)
        b.box(0.075f, 0.045f, 0.035f, 0.012f, 0.025f, 0.02f, 1f, 0.3f, 0.1f, emissive = true)
        b.box(0.075f, -0.045f, 0.035f, 0.012f, 0.025f, 0.02f, 1f, 0.3f, 0.1f, emissive = true)
        m.pop()
        for (side in SIDES) {
            m.push(); m.translate(0.05f, side * 0.15f, 0.45f); m.rotY(0.6f - walk * 0.3f)
            b.box(0.08f, 0f, 0f, 0.16f, 0.04f, 0.04f, r * 0.7f, g * 0.7f, bl * 0.7f)
            m.pop()
        }
    }

    private fun brute(b: DynBatch, walk: Float, attack: Boolean, time: Float) {
        val m = b.xf
        val r = 0.5f; val g = 0.2f; val bl = 0.15f
        for (side in SIDES) {
            m.push(); m.translate(0f, side * 0.15f, 0.42f); m.rotY(walk * 0.45f * side)
            b.box(0f, 0f, -0.21f, 0.17f, 0.16f, 0.42f, r * 0.7f, g * 0.7f, bl * 0.7f, Tex.SKIN)
            b.box(0.04f, 0f, -0.4f, 0.24f, 0.18f, 0.06f, 0.25f, 0.2f, 0.18f)
            m.pop()
        }
        b.box(0f, 0f, 0.48f, 0.26f, 0.38f, 0.16f, r * 0.8f, g * 0.8f, bl * 0.8f, Tex.SKIN)
        b.box(0.02f, 0f, 0.7f, 0.42f, 0.6f, 0.36f, r, g, bl, Tex.SKIN, texScale = 2f)
        // Plaques osseuses
        for (side in SIDES) b.box(0f, side * 0.27f, 0.9f, 0.3f, 0.16f, 0.08f, 0.85f, 0.78f, 0.6f)
        b.box(-0.18f, 0f, 0.82f, 0.14f, 0.4f, 0.2f, 0.85f, 0.78f, 0.6f)
        for (i in 0 until 3) b.octa(-0.2f, -0.12f + i * 0.12f, 0.95f, 0.04f, 0.04f, 0.09f, 0.9f, 0.85f, 0.7f)
        // Tête basse
        b.box(0.22f, 0f, 0.84f, 0.16f, 0.2f, 0.15f, r * 0.9f, g * 0.9f, bl, Tex.SKIN)
        b.box(0.302f, 0.05f, 0.87f, 0.01f, 0.04f, 0.025f, 1f, 0.55f, 0.1f, emissive = true)
        b.box(0.302f, -0.05f, 0.87f, 0.01f, 0.04f, 0.025f, 1f, 0.55f, 0.1f, emissive = true)
        b.box(0.29f, 0f, 0.79f, 0.04f, 0.14f, 0.03f, 0.9f, 0.85f, 0.75f)
        // Bras et griffes
        val swing = if (attack) sin(time * 18f) * 0.9f else walk * 0.35f
        for (side in SIDES) {
            m.push(); m.translate(0.02f, side * 0.36f, 0.82f); m.rotY(0.4f + swing * side)
            b.box(0.0f, 0f, -0.2f, 0.16f, 0.14f, 0.42f, r * 0.9f, g * 0.9f, bl, Tex.SKIN)
            for (k in -1..1) b.octa(0.06f, k * 0.04f, -0.44f, 0.02f, 0.02f, 0.08f, 0.95f, 0.92f, 0.85f)
            m.pop()
        }
    }

    private fun drone(b: DynBatch, time: Float, alive: Boolean, attack: Boolean) {
        val m = b.xf
        val spin = if (alive) time * 3f else 0f
        b.octa(0f, 0f, 0.2f, 0.17f, 0.17f, 0.2f, 0.45f, 0.47f, 0.5f)
        b.box(0f, 0f, 0.2f, 0.2f, 0.2f, 0.12f, 0.3f, 0.32f, 0.35f, Tex.METAL)
        val eye = if (!alive) 0.1f else if (attack) 1.6f else 1f
        b.box(0.105f, 0f, 0.2f, 0.02f, 0.08f, 0.06f, eye, 0.12f * eye, 0.1f * eye, emissive = true)
        m.push(); m.translate(0f, 0f, 0.2f); m.rotZ(spin)
        for (i in 0 until 4) {
            m.push(); m.rotZ(i * 1.5708f)
            b.box(0.22f, 0f, 0f, 0.1f, 0.04f, 0.03f, 0.55f, 0.57f, 0.6f)
            b.box(0.27f, 0f, 0f, 0.03f, 0.08f, 0.05f, 0.2f, 0.6f, 1f, emissive = alive)
            m.pop()
        }
        m.pop()
        b.box(0f, 0f, 0.03f, 0.08f, 0.08f, 0.06f, 0.3f, 0.7f, 1f, emissive = alive)
    }

    private fun boss(b: DynBatch, walk: Float, attack: Boolean, time: Float, e: Enemy) {
        val m = b.xf
        val r = 0.55f; val g = 0.16f; val bl = 0.18f
        val rage = if (e.hp < e.type.hp / 2) 1f else 0f
        for (side in SIDES) {
            m.push(); m.translate(0f, side * 0.38f, 0.95f); m.rotY(walk * 0.35f * side)
            b.box(0f, 0f, -0.45f, 0.36f, 0.34f, 0.9f, 0.3f, 0.3f, 0.33f, Tex.METAL, texScale = 2f)
            b.box(0.08f, 0f, -0.88f, 0.55f, 0.42f, 0.14f, 0.2f, 0.2f, 0.22f)
            b.box(0.05f, 0f, -0.45f, 0.3f, 0.36f, 0.1f, 1f, 0.5f, 0.1f, emissive = true)
            m.pop()
        }
        b.box(0f, 0f, 1.05f, 0.5f, 0.85f, 0.3f, r * 0.8f, g * 0.8f, bl * 0.8f, Tex.SKIN, texScale = 2f)
        b.box(0f, 0f, 1.55f, 0.95f, 1.35f, 0.9f, r, g, bl, Tex.SKIN, texScale = 3f)
        // Cœur du réacteur incrusté
        val pulse = 0.7f + 0.3f * sin(time * (5f + rage * 5f))
        b.octa(0.5f, 0f, 1.55f, 0.12f, 0.22f, 0.22f, 0.5f * pulse, 1f * pulse, 0.3f * pulse, emissive = true)
        b.box(0.46f, 0f, 1.55f, 0.06f, 0.5f, 0.5f, 0.25f, 0.25f, 0.28f, Tex.METAL)
        // Nacelles lance-roquettes
        for (side in SIDES) {
            b.box(0f, side * 0.78f, 2.0f, 0.5f, 0.38f, 0.38f, 0.32f, 0.34f, 0.36f, Tex.METAL)
            for (k in 0 until 3) b.cylX(0.2f, 0.32f, side * 0.78f + (k - 1) * 0.11f, 2.05f, 0.045f, 6, 0.1f, 0.1f, 0.1f)
            b.box(0.26f, side * 0.78f, 1.92f, 0.02f, 0.3f, 0.05f, 1f, 0.2f * (1f - rage), 0.1f, emissive = true)
        }
        // Tête
        b.box(0.32f, 0f, 2.12f, 0.36f, 0.42f, 0.32f, r * 0.9f, g * 0.9f, bl, Tex.SKIN)
        for (k in -1..1) b.box(0.505f, k * 0.12f, 2.16f, 0.02f, 0.06f, 0.05f, 1f, 0.9f, 0.2f, emissive = true)
        for (side in SIDES) b.octa(0.3f, side * 0.24f, 2.34f, 0.06f, 0.06f, 0.18f, 0.9f, 0.85f, 0.7f)
        // Épines dorsales
        for (i in 0 until 4) b.octa(-0.5f, -0.45f + i * 0.3f, 1.85f, 0.07f, 0.07f, 0.25f, 0.9f, 0.85f, 0.7f)
        // Bras-griffes
        val swing = if (attack) sin(time * 10f) * 0.7f else walk * 0.25f
        for (side in SIDES) {
            m.push(); m.translate(0.1f, side * 0.72f, 1.7f); m.rotY(0.5f + swing * side)
            b.box(0f, 0f, -0.35f, 0.3f, 0.28f, 0.7f, r * 0.9f, g * 0.9f, bl, Tex.SKIN)
            for (k in -1..1) b.octa(0.1f, k * 0.08f, -0.78f, 0.04f, 0.04f, 0.16f, 0.95f, 0.9f, 0.8f)
            m.pop()
        }
    }

    private fun barrel(b: DynBatch, alive: Boolean) {
        if (!alive) {
            b.cyl(0f, 0f, 0f, 0.08f, 0.21f, 8, 0.12f, 0.12f, 0.1f)
            return
        }
        b.cyl(0f, 0f, 0f, 0.55f, 0.2f, 8, 0.22f, 0.45f, 0.14f, Tex.METAL)
        b.cyl(0f, 0f, 0.12f, 0.17f, 0.205f, 8, 0.85f, 0.7f, 0.1f)
        b.cyl(0f, 0f, 0.38f, 0.43f, 0.205f, 8, 0.85f, 0.7f, 0.1f)
        b.cyl(0f, 0f, 0.55f, 0.565f, 0.15f, 8, 0.45f, 1f, 0.2f, emissive = true)
    }

    fun lightPillar(b: DynBatch, x: Float, y: Float, z: Float, time: Float, lr: Float, lg: Float, lb: Float) {
        b.setLight(lr, lg, lb)
        val m = b.xf
        m.reset(); m.translate(x, y, z)
        b.box(0f, 0f, 0.08f, 0.42f, 0.42f, 0.16f, 0.35f, 0.37f, 0.4f, Tex.METAL)
        b.box(0f, 0f, 0.9f, 0.24f, 0.24f, 1.5f, 0.25f, 0.27f, 0.3f, Tex.METAL, texScale = 2f)
        val p = 0.85f + 0.15f * sin(time * 2f + x)
        for (i in 0 until 4) {
            m.push(); m.rotZ(i * 1.5708f)
            b.box(0.125f, 0f, 0.9f, 0.02f, 0.08f, 1.3f, 0.3f * p, 0.9f * p, 1f * p, emissive = true)
            m.pop()
        }
        b.box(0f, 0f, 1.7f, 0.34f, 0.34f, 0.12f, 0.35f, 0.37f, 0.4f, Tex.METAL)
    }

    // ------------------------------------------------------------------ portes

    fun door(b: DynBatch, d: Door, tex: Int, lr: Float, lg: Float, lb: Float) {
        val bottom = d.bottom(); val top = d.top
        if (top - bottom < 0.01f) return
        b.flat = true
        try { doorFaces(b, d, tex, lr, lg, lb, bottom, top) } finally { b.flat = false }
    }

    private fun doorFaces(b: DynBatch, d: Door, tex: Int, lr: Float, lg: Float, lb: Float, bottom: Float, top: Float) {
        b.setLight(lr, lg, lb)
        val m = b.xf
        m.reset()
        val x0 = d.cx.toFloat(); val x1 = x0 + 1f; val y0 = d.cy.toFloat(); val y1 = y0 + 1f
        val full = top - d.floor
        val vs: Float; val vo: Float
        if (d.secret) { vs = top - bottom; vo = -(top - bottom) - (bottom - d.floor) }
        else { vs = (top - bottom) / full; vo = 1f - vs }
        b.quad(x0, y0, bottom, x1, y0, bottom, x1, y0, top, x0, y0, top, 1f, 1f, 1f, tex, false, 1f, vs, 0f, vo)
        b.quad(x1, y1, bottom, x0, y1, bottom, x0, y1, top, x1, y1, top, 1f, 1f, 1f, tex, false, 1f, vs, 0f, vo)
        b.quad(x0, y1, bottom, x0, y0, bottom, x0, y0, top, x0, y1, top, 1f, 1f, 1f, tex, false, 1f, vs, 0f, vo)
        b.quad(x1, y0, bottom, x1, y1, bottom, x1, y1, top, x1, y0, top, 1f, 1f, 1f, tex, false, 1f, vs, 0f, vo)
        b.quad(x0, y1, bottom, x1, y1, bottom, x1, y0, bottom, x0, y0, bottom, 0.6f, 0.6f, 0.6f, tex, false, 1f, 0.05f, 0f, 0.95f)
    }

    // ------------------------------------------------------------------ objets

    fun pickup(b: DynBatch, p: Pickup, time: Float, lr: Float, lg: Float, lb: Float) {
        b.setLight(lr * 1.2f + 0.15f, lg * 1.2f + 0.15f, lb * 1.2f + 0.15f)
        val m = b.xf
        m.reset()
        val bob = sin(time * 2.2f + p.phase) * 0.035f
        m.translate(p.x, p.y, p.z + 0.12f + bob)
        m.rotZ(time * 1.4f + p.phase)
        when (p.ch) {
            'h' -> medkit(b, 0.8f)
            'H' -> medkit(b, 1.25f)
            'a' -> armor(b, 0.2f, 0.65f, 0.25f, 1f, 0.85f, 0.2f)
            'A' -> armor(b, 0.2f, 0.35f, 0.9f, 1f, 0.8f, 0.2f)
            'm' -> ammoBox(b, 0.16f, 0.6f, 0.5f, 0.2f)
            'q' -> ammoBox(b, 0.11f, 0.6f, 0.5f, 0.2f)
            'n' -> { ammoBox(b, 0.2f, 0.7f, 0.15f, 0.1f); for (k in -1..1) b.cyl(k * 0.05f, 0f, 0.08f, 0.16f, 0.022f, 6, 0.85f, 0.2f, 0.1f) }
            'o' -> for (k in -1..1 step 2) {
                b.cylX(-0.14f, 0.12f, k * 0.05f, 0.05f, 0.04f, 6, 0.3f, 0.4f, 0.2f)
                b.cylX(0.12f, 0.2f, k * 0.05f, 0.05f, 0.04f, 6, 0.6f, 0.6f, 0.62f, rEnd = 0.005f)
            }
            'p' -> {
                b.box(0f, 0f, 0.07f, 0.16f, 0.12f, 0.14f, 0.25f, 0.3f, 0.4f, Tex.METAL)
                b.box(0f, 0f, 0.15f, 0.1f, 0.08f, 0.03f, 0.3f, 0.7f, 1f, emissive = true)
            }
            'S' -> { m.scale(0.7f, 0.7f, 0.7f); m.translate(-0.15f, 0f, 0.1f); weaponModel(b, Weapons.SHOTGUN, 0f, 0f, 0f, time) }
            'G' -> { m.scale(0.7f, 0.7f, 0.7f); m.translate(-0.15f, 0f, 0.1f); weaponModel(b, Weapons.CHAINGUN, 0f, 0f, 0f, time) }
            'K' -> { m.scale(0.7f, 0.7f, 0.7f); m.translate(-0.15f, 0f, 0.1f); weaponModel(b, Weapons.ROCKET, 0f, 0f, 0f, time) }
            'U' -> { m.scale(0.7f, 0.7f, 0.7f); m.translate(-0.15f, 0f, 0.1f); weaponModel(b, Weapons.ANNIHILATOR, 0f, 0f, 0f, time) }
            'r' -> keycard(b, 1f, 0.15f, 0.1f)
            'u' -> keycard(b, 0.2f, 0.45f, 1f)
            'y' -> keycard(b, 1f, 0.85f, 0.15f)
        }
    }

    private fun medkit(b: DynBatch, s: Float) {
        b.box(0f, 0f, 0.06f * s, 0.2f * s, 0.15f * s, 0.12f * s, 0.92f, 0.92f, 0.9f)
        b.box(0f, 0f, 0.121f * s, 0.12f * s, 0.035f * s, 0.01f, 1f, 0.1f, 0.1f, emissive = true)
        b.box(0f, 0f, 0.121f * s, 0.035f * s, 0.1f * s, 0.01f, 1f, 0.1f, 0.1f, emissive = true)
        b.box(0f, 0.076f * s, 0.06f * s, 0.1f * s, 0.01f, 0.04f * s, 1f, 0.1f, 0.1f, emissive = true)
    }

    private fun armor(b: DynBatch, r: Float, g: Float, bl: Float, er: Float, eg: Float, eb: Float) {
        b.box(0f, 0f, 0.15f, 0.12f, 0.3f, 0.28f, r, g, bl, Tex.METAL)
        b.box(0f, 0.16f, 0.24f, 0.11f, 0.06f, 0.1f, r * 0.8f, g * 0.8f, bl * 0.8f)
        b.box(0f, -0.16f, 0.24f, 0.11f, 0.06f, 0.1f, r * 0.8f, g * 0.8f, bl * 0.8f)
        b.box(0.065f, 0f, 0.15f, 0.01f, 0.2f, 0.03f, er, eg, eb, emissive = true)
    }

    private fun ammoBox(b: DynBatch, s: Float, r: Float, g: Float, bl: Float) {
        b.box(0f, 0f, s * 0.35f, s, s * 0.6f, s * 0.7f, r, g, bl, Tex.METAL)
        b.box(0f, 0f, s * 0.71f, s * 0.6f, s * 0.3f, 0.01f, 0.15f, 0.15f, 0.15f)
    }

    private fun keycard(b: DynBatch, r: Float, g: Float, bl: Float) {
        b.box(0f, 0f, 0.12f, 0.16f, 0.02f, 0.11f, r, g, bl, emissive = true)
        b.box(0.03f, 0.011f, 0.14f, 0.05f, 0.003f, 0.04f, 0.95f, 0.95f, 0.9f, emissive = true)
    }

    // ------------------------------------------------------------------ projectiles et débris

    fun projectile(b: DynBatch, p: Projectile, time: Float) {
        val m = b.xf
        m.reset()
        m.translate(p.x, p.y, p.z)
        m.rotZ(atan2(p.vy, p.vx))
        val h = sqrt(p.vx * p.vx + p.vy * p.vy)
        m.rotY(-atan2(p.vz, h))
        when (p.kind) {
            Projectile.ROCKET, Projectile.BOSS_ROCKET -> {
                b.setLight(0.8f, 0.8f, 0.8f)
                val red = p.kind == Projectile.BOSS_ROCKET
                b.cylX(-0.14f, 0.08f, 0f, 0f, 0.035f, 6, if (red) 0.6f else 0.3f, if (red) 0.15f else 0.4f, if (red) 0.1f else 0.2f)
                b.cylX(0.08f, 0.16f, 0f, 0f, 0.035f, 6, 0.6f, 0.6f, 0.62f, rEnd = 0.005f)
                b.octa(-0.17f, 0f, 0f, 0.06f, 0.035f, 0.035f, 1f, 0.7f, 0.2f, emissive = true)
            }
            Projectile.ANNIHIL -> {
                m.rotX(time * 6f)
                b.octa(0f, 0f, 0f, 0.2f, 0.2f, 0.2f, 0.6f, 1f, 0.6f, emissive = true)
                m.rotZ(0.785f)
                b.octa(0f, 0f, 0f, 0.14f, 0.14f, 0.26f, 0.9f, 1f, 0.9f, emissive = true)
            }
            Projectile.ACID -> b.octa(0f, 0f, 0f, 0.08f, 0.06f, 0.06f, 0.5f, 1f, 0.2f, emissive = true)
            Projectile.PLASMA -> b.octa(0f, 0f, 0f, 0.1f, 0.05f, 0.05f, 1f, 0.35f, 0.3f, emissive = true)
        }
    }

    // ------------------------------------------------------------------ armes (vue subjective et objets)

    /**
     * Modèle d'arme dans le repère courant. [fire] 0..1 (animation de tir), [spin] rotation des canons,
     * [charge] 0..1 charge de l'annihilateur.
     */
    fun weaponModel(b: DynBatch, w: Int, fire: Float, spin: Float, charge: Float, time: Float) {
        val m = b.xf
        val dark = 0.26f
        when (w) {
            Weapons.FIST -> {
                val punch = sin(min(1f, fire) * 3.14159f)
                m.translate(punch * 0.16f, punch * 0.06f, punch * 0.03f)
                b.box(-0.08f, 0f, -0.01f, 0.24f, 0.09f, 0.09f, 0.35f, 0.37f, 0.42f, Tex.METAL)
                b.box(0.07f, 0f, 0f, 0.1f, 0.11f, 0.1f, 0.45f, 0.47f, 0.52f, Tex.METAL)
                for (k in -1..1) b.box(0.122f, k * 0.03f, 0.03f, 0.01f, 0.02f, 0.02f, 0.3f, 0.8f, 1f, emissive = true)
                b.box(-0.04f, 0f, 0.05f, 0.1f, 0.06f, 0.02f, 0.3f, 0.8f, 1f, emissive = true)
            }
            Weapons.PISTOL -> {
                m.translate(-fire * 0.04f, 0f, 0f)
                m.rotY(-fire * 0.4f)
                b.box(0.05f, 0f, 0.03f, 0.2f, 0.045f, 0.045f, 0.5f, 0.52f, 0.56f, Tex.METAL)
                b.box(0.05f, 0f, 0.0f, 0.18f, 0.04f, 0.025f, 0.28f, 0.29f, 0.31f)
                m.push(); m.translate(-0.02f, 0f, -0.02f); m.rotY(0.25f)
                b.box(0f, 0f, -0.04f, 0.05f, 0.04f, 0.1f, 0.24f, 0.2f, 0.17f)
                m.pop()
                b.box(0.152f, 0f, 0.03f, 0.008f, 0.02f, 0.02f, 0.05f, 0.05f, 0.05f)
                b.box(0.12f, 0f, 0.055f, 0.015f, 0.01f, 0.008f, 1f, 0.5f, 0.1f, emissive = true)
                hand(b, -0.04f, -0.06f)
            }
            Weapons.SHOTGUN -> {
                val pump = if (fire < 0.6f) sin(fire / 0.6f * 3.14159f) * 0.07f else 0f
                m.translate(-fire * 0.06f, 0f, fire * 0.02f)
                b.cylX(-0.05f, 0.42f, 0f, 0.03f, 0.022f, 6, 0.2f, 0.21f, 0.23f)
                b.cylX(-0.05f, 0.38f, 0f, -0.01f, 0.018f, 6, 0.15f, 0.15f, 0.16f)
                b.box(0.18f - pump, 0f, -0.012f, 0.12f, 0.05f, 0.04f, 0.42f, 0.26f, 0.12f)
                b.box(-0.12f, 0f, 0.0f, 0.18f, 0.05f, 0.07f, 0.4f, 0.24f, 0.11f)
                b.box(-0.23f, 0f, -0.03f, 0.1f, 0.05f, 0.1f, 0.35f, 0.21f, 0.1f)
                hand(b, -0.08f, -0.1f)
            }
            Weapons.CHAINGUN -> {
                m.translate(-fire * 0.02f, 0f, 0f)
                b.box(-0.06f, 0f, 0.0f, 0.2f, 0.1f, 0.1f, 0.3f, 0.32f, 0.35f, Tex.METAL)
                m.push(); m.translate(0.04f, 0f, 0f); m.rotX(spin)
                val heat = min(1f, spin * 0.02f)
                for (k in 0 until 6) {
                    val a = k * 1.0472f
                    b.cylX(0f, 0.36f, sin(a) * 0.032f, kotlin.math.cos(a) * 0.032f, 0.012f, 5, 0.18f, 0.18f, 0.2f)
                }
                b.cylX(0.3f, 0.32f, 0f, 0f, 0.05f, 8, 0.3f, 0.3f, 0.32f)
                b.box(0.37f, 0f, 0f, 0.01f, 0.05f, 0.05f, 1f * heat + 0.05f, 0.4f * heat, 0.1f * heat, emissive = true)
                m.pop()
                b.box(-0.06f, 0f, 0.07f, 0.12f, 0.03f, 0.04f, dark, dark, dark)
                hand(b, -0.07f, -0.1f)
            }
            Weapons.ROCKET -> {
                m.translate(-fire * 0.08f, 0f, fire * 0.03f)
                b.cylX(-0.2f, 0.36f, 0f, 0.02f, 0.06f, 8, 0.3f, 0.36f, 0.2f)
                b.cylX(0.33f, 0.4f, 0f, 0.02f, 0.07f, 8, 0.2f, 0.2f, 0.22f)
                b.cylX(-0.24f, -0.2f, 0f, 0.02f, 0.07f, 8, 0.2f, 0.2f, 0.22f)
                b.box(0.06f, 0f, 0.1f, 0.1f, 0.03f, 0.04f, 0.15f, 0.15f, 0.16f)
                b.box(0.06f, 0f, 0.125f, 0.02f, 0.015f, 0.01f, 1f, 0.15f, 0.1f, emissive = true)
                b.box(0.0f, 0f, -0.07f, 0.05f, 0.04f, 0.08f, dark, dark, dark)
                hand(b, -0.02f, -0.12f)
            }
            Weapons.ANNIHILATOR -> {
                m.translate(-fire * 0.1f, 0f, 0f)
                b.box(-0.02f, 0f, 0f, 0.32f, 0.14f, 0.12f, 0.25f, 0.27f, 0.3f, Tex.METAL)
                val glow = 0.55f + 0.25f * sin(time * 6f) + charge * 0.8f
                b.octa(0.04f, 0f, 0.085f, 0.07f, 0.05f, 0.05f, 0.4f * glow, 1f * glow, 0.5f * glow, emissive = true)
                for (k in 0 until 4) b.box(-0.1f + k * 0.06f, 0f, 0f, 0.025f, 0.16f, 0.14f, 0.5f, 0.42f, 0.2f)
                for (k in -1..1 step 2) b.box(0.2f, k * 0.05f, 0f, 0.14f, 0.025f, 0.03f, 0.3f, 0.32f, 0.35f)
                b.box(0.2f, 0f, 0.05f, 0.14f, 0.025f, 0.025f, 0.3f, 0.32f, 0.35f)
                b.octa(0.27f, 0f, 0f, 0.03f + charge * 0.04f, 0.03f + charge * 0.04f, 0.03f + charge * 0.04f, 0.6f, 1f, 0.6f, emissive = true)
                hand(b, -0.08f, -0.12f)
            }
        }
    }

    private fun hand(b: DynBatch, x: Float, z: Float) {
        b.box(x, 0f, z, 0.07f, 0.075f, 0.075f, 0.32f, 0.3f, 0.28f, Tex.SKIN)
        b.box(x - 0.13f, 0f, z - 0.03f, 0.2f, 0.09f, 0.09f, 0.85f, 0.45f, 0.12f, Tex.METAL)
        b.box(x - 0.05f, 0f, z - 0.01f, 0.03f, 0.1f, 0.1f, 0.3f, 0.32f, 0.35f)
    }

    /** Arme en vue subjective, attachée à la caméra. */
    fun viewWeapon(
        b: DynBatch, p: Player, time: Float,
        fx: Float, fy: Float, fz: Float, rx: Float, ry: Float, rz: Float, ux: Float, uy: Float, uz: Float,
        ex: Float, ey: Float, ez: Float, lr: Float, lg: Float, lb: Float,
    ) {
        b.setLight(lr, lg, lb)
        val m = b.xf
        // Repère caméra : x = avant, y = gauche, z = haut
        m.setBasis(fx, fy, fz, -rx, -ry, -rz, ux, uy, uz, ex, ey, ez)
        val bobX = sin(p.bobPhase) * 0.012f * p.bobAmount
        val bobZ = abs(sin(p.bobPhase)) * 0.012f * p.bobAmount
        val wd = Weapons.defs[p.weapon]
        val drop = p.switchAnim * 0.3f
        m.translate(0.34f - p.fireAnim * wd.recoil * 0.1f, -0.12f + bobX, -0.135f - bobZ - drop + p.fireAnim * wd.recoil * 0.03f)
        m.rotY(-p.fireAnim * wd.recoil * 0.5f + 0.05f)
        m.rotZ(0.16f)
        m.scale(0.62f, 0.62f, 0.62f)
        val fireT = if (p.weapon == Weapons.FIST) 1f - p.fireAnim else p.fireAnim
        val charge = if (p.chargeTime > 0f) 1f - p.chargeTime / 0.35f else 0f
        weaponModel(b, p.weapon, if (p.weapon == Weapons.FIST && p.fireAnim <= 0f) 0f else fireT, p.spin * time * 25f, charge, time)
        if (p.flash > 0f && p.weapon != Weapons.FIST) {
            val tip = when (p.weapon) { Weapons.PISTOL -> 0.19f; Weapons.SHOTGUN -> 0.45f; Weapons.CHAINGUN -> 0.41f; Weapons.ROCKET -> 0.43f; else -> 0.32f }
            val c = if (p.weapon == Weapons.ANNIHILATOR) FLASH_GREEN else FLASH_YELLOW
            m.rotX(time * 40f)
            val fs = if (p.weapon == Weapons.ANNIHILATOR) 0.5f else 1f
            b.octa(tip, 0f, 0.02f, 0.06f * fs, 0.05f * fs, 0.05f * fs, c[0], c[1], c[2], emissive = true)
            b.octa(tip + 0.03f, 0f, 0.02f, 0.03f * fs, 0.08f * fs, 0.02f * fs, c[0], c[1], c[2], emissive = true)
        }
    }

    fun debrisCube(b: DynBatch, x: Float, y: Float, z: Float, s: Float, rot: Float, r: Float, g: Float, bl: Float) {
        val m = b.xf
        m.reset(); m.translate(x, y, z); m.rotZ(rot); m.rotX(rot * 0.7f)
        b.box(0f, 0f, 0f, s * 2f, s * 2f, s * 2f, r, g, bl)
    }

    @Suppress("unused")
    private fun clampLight(v: Float) = max(0f, v)
}
