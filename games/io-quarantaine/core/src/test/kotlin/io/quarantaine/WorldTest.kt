package io.quarantaine

import io.quarantaine.game.Enemies
import io.quarantaine.game.Hit
import io.quarantaine.game.Input
import io.quarantaine.game.NoEvents
import io.quarantaine.game.Physics
import io.quarantaine.game.Weapons
import io.quarantaine.game.World
import io.quarantaine.math.Rng
import io.quarantaine.world.Level
import io.quarantaine.world.Levels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldTest {

    @Test
    fun allLevelsParse() {
        for (def in Levels.all) {
            val lv = Level(def)
            assertTrue(def.rows.all { it.length == lv.w }, "largeur constante ${def.subtitle}")
            assertEquals(1, lv.things.count { it.ch == 'P' }, "un seul départ ${def.subtitle}")
            val start = lv.cellAt(lv.startX, lv.startY)!!
            assertFalse(start.solid)
            if (def.boss) assertEquals(1, lv.things.count { it.ch == 'M' })
            else assertTrue(lv.cells.any { it.exit }, "sortie ${def.subtitle}")
        }
    }

    @Test
    fun startIsFreeOfCollision() {
        for (def in Levels.all) {
            val w = World(def, 1, NoEvents)
            assertFalse(w.phys.blocked(w.player.x, w.player.y, World.PLAYER_R, w.player.z, World.PLAYER_H, false), def.subtitle)
        }
    }

    @Test
    fun raycastHitsWall() {
        val w = World(Levels.all[0], 1, NoEvents)
        val p = w.player
        val hit = Hit()
        // Vers l'ouest depuis le départ (3,6) : mur de roche à x = 1.0
        w.phys.trace(p.x, p.y, p.eyeZ, -1f, 0f, 0f, 50f, hit)
        assertEquals(Hit.WALL, hit.kind)
        assertEquals(p.x - 1f, hit.t, 0.01f)
        // Vers le haut : ciel au-dessus de la zone extérieure
        w.phys.trace(p.x, p.y, p.eyeZ, 0f, 0f, 1f, 50f, hit)
        assertEquals(Hit.SKY, hit.kind)
    }

    @Test
    fun playerCannotWalkThroughWalls() {
        val w = World(Levels.all[0], 1, NoEvents)
        val input = Input()
        input.moveY = 1f
        w.player.yaw = Math.PI.toFloat() // vers l'ouest
        repeat(300) { w.update(1f / 60f, input) }
        assertTrue(w.player.x > 1f + World.PLAYER_R - 0.01f, "x=${w.player.x}")
    }

    @Test
    fun climbsStairsButNotLedges() {
        val w = World(Levels.all[0], 1, NoEvents)
        val phys = w.phys
        // Marche de 0,25 : franchissable ; caisse de 0,75 : non
        val step = Physics(w.level)
        val lv = w.level
        val stair = lv.cells.indexOfFirst { it.ch == '1' }
        val crate = lv.cells.indexOfFirst { it.ch == 'C' }
        assertFalse(step.cellBlocks(lv.cells[stair], 0f, World.PLAYER_H, false))
        assertTrue(phys.cellBlocks(lv.cells[crate], 0f, World.PLAYER_H, false))
    }

    @Test
    fun doorsOpenWhenApproached() {
        val w = World(Levels.all[0], 1, NoEvents)
        val door = w.level.doors.first { w.level.cell(it.cx, it.cy)!!.ch == 'D' && it.cx == 13 }
        val input = Input()
        input.moveY = 1f
        w.player.yaw = 0f
        w.player.y = door.cy + 0.5f
        repeat(400) { w.update(1f / 60f, input) }
        assertTrue(w.player.x > door.cx + 1f, "le joueur a franchi la porte (x=${w.player.x})")
    }

    @Test
    fun weaponsFireAndConsumeAmmo() {
        val w = World(Levels.all[0], 1, NoEvents)
        val input = Input()
        input.fire = true
        val before = w.player.ammo[0]
        repeat(60) { w.update(1f / 60f, input) }
        assertTrue(w.player.ammo[0] < before)
        // Toutes les armes
        for (i in 0 until Weapons.COUNT) w.player.owned[i] = true
        w.player.ammo[1] = 20; w.player.ammo[2] = 10; w.player.ammo[3] = 200
        for (wpn in 0 until Weapons.COUNT) {
            input.select = wpn
            repeat(90) { w.update(1f / 60f, input); input.select = -1 }
            assertEquals(wpn, w.player.weapon)
        }
    }

    @Test
    fun randomPlaySmokeTest() {
        for (diff in 0..2) for (def in Levels.all) {
            val w = World(def, diff, NoEvents, seed = 99L + diff)
            val rng = Rng(7L + def.index)
            val input = Input()
            for (all in 0 until Weapons.COUNT) w.player.owned[all] = true
            w.player.ammo[0] = 200; w.player.ammo[1] = 50; w.player.ammo[2] = 50; w.player.ammo[3] = 300
            var t = 0f
            while (t < 45f) {
                if (rng.chance(0.03f)) { input.moveX = rng.signed(); input.moveY = rng.signed() }
                input.lookYaw = rng.signed() * 0.05f
                input.fire = rng.chance(0.4f)
                if (rng.chance(0.01f)) input.select = rng.int(Weapons.COUNT)
                w.update(1f / 30f, input)
                input.clearImpulses()
                t += 1f / 30f
                if (w.player.dead) { w.player.dead = false; w.player.health = 100 }
            }
            assertTrue(w.enemies.isNotEmpty())
        }
    }

    @Test
    fun enemiesHuntThePlayer() {
        val w = World(Levels.all[0], 1, NoEvents)
        val input = Input()
        // Le joueur reste sur place et tire en l'air pour faire du bruit
        w.player.pitch = 0.7f
        var t = 0f
        while (t < 20f) { input.fire = true; w.update(1f / 30f, input); t += 1f / 30f }
        val awake = w.enemies.count { it.type.id != Enemies.BARREL && it.state != 0 }
        assertTrue(awake >= 2, "ennemis réveillés : $awake")
        assertTrue(w.player.health < 100 || w.player.armor < 100 || w.player.dead, "le joueur a été attaqué")
    }

    @Test
    fun bossLevelCompletesWhenBossDies() {
        val w = World(Levels.all[3], 1, NoEvents)
        val boss = w.boss!!
        w.hurtEnemy(boss, 100000, boss.x, boss.y - 3f, true, 0f)
        assertFalse(boss.alive)
        var t = 0f
        val input = Input()
        while (t < 6f && !w.completed) { w.update(1f / 30f, input); t += 1f / 30f }
        assertTrue(w.completed)
    }

    @Test
    fun inventorySerializationRoundTrip() {
        val w = World(Levels.all[0], 1, NoEvents)
        val p = w.player
        p.health = 73; p.armor = 40; p.owned[Weapons.SHOTGUN] = true; p.ammo[1] = 12; p.weapon = Weapons.SHOTGUN
        val s = p.serialize()
        val q = World(Levels.all[1], 1, NoEvents).player
        assertTrue(q.deserialize(s))
        assertEquals(73, q.health); assertEquals(40, q.armor); assertTrue(q.owned[Weapons.SHOTGUN]); assertEquals(12, q.ammo[1]); assertEquals(Weapons.SHOTGUN, q.weapon)
    }
}
