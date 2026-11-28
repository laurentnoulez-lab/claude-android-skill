package io.quarantaine.game

import io.quarantaine.audio.Sfx
import io.quarantaine.math.Rng
import io.quarantaine.math.clamp
import io.quarantaine.math.wrapAngle
import io.quarantaine.world.Door
import io.quarantaine.world.Level
import io.quarantaine.world.LevelDef
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Notifications émises par la simulation vers le jeu (son, vibrations, messages). */
interface WorldEvents {
    fun sound(id: Int, x: Float, y: Float, vol: Float = 1f)
    fun soundUi(id: Int, vol: Float = 1f)
    fun vibrate(ms: Int, strength: Float)
    fun message(text: String)
    fun shake(amount: Float)
}

object NoEvents : WorldEvents {
    override fun sound(id: Int, x: Float, y: Float, vol: Float) {}
    override fun soundUi(id: Int, vol: Float) {}
    override fun vibrate(ms: Int, strength: Float) {}
    override fun message(text: String) {}
    override fun shake(amount: Float) {}
}

/** Commandes du joueur pour une image. */
class Input {
    var moveX = 0f
    var moveY = 0f
    var lookYaw = 0f
    var lookPitch = 0f
    var fire = false
    var select = -1
    var next = false

    fun clearImpulses() { lookYaw = 0f; lookPitch = 0f; select = -1; next = false }
}

private class PendingBoom(val x: Float, val y: Float, val z: Float, val radius: Float, val damage: Int, val fromPlayer: Boolean, val hurtsPlayer: Boolean, var timer: Float, val big: Boolean)

class World(
    val def: LevelDef,
    val difficulty: Int,
    private val ev: WorldEvents,
    carry: Player? = null,
    var aimAssist: Boolean = true,
    seed: Long = 1234L,
) {
    val level = Level(def)
    val phys = Physics(level)
    val player = Player()
    val enemies = ArrayList<Enemy>()
    val pickups = ArrayList<Pickup>()
    val props = ArrayList<Prop>()
    val projectiles = ArrayList<Projectile>()
    val debris = Particles(300)
    val glows = Particles(480)
    val lights = ArrayList<DynLight>()
    private val booms = ArrayList<PendingBoom>()
    val rng = Rng(seed)

    var time = 0f
    var kills = 0
    var killsTotal = 0
    var secretsFound = 0
    var completed = false
    var bossDead = false
    var bossDeathTime = 0f
    var boss: Enemy? = null
    private var completeTimer = -1f

    private val groundDist = IntArray(level.w * level.h)
    private val airDist = IntArray(level.w * level.h)
    private var flowTimer = 0f
    private var noiseTimer = 0f
    private var seenTimer = 0f
    private var painSoundTimer = 0f
    private var lockedMsgTimer = 0f
    private val tmp2 = FloatArray(2)
    private val hit = Hit()

    companion object {
        const val PLAYER_R = 0.24f
        const val PLAYER_H = 0.62f
        const val EYE = 0.52f
        const val SPEED = 4.2f
        const val GRAVITY = 18f
        private const val INF = Int.MAX_VALUE / 4
    }

    init {
        player.x = level.startX; player.y = level.startY; player.z = level.startZ
        player.eyeZ = player.z + EYE
        player.yaw = def.startYawDeg * 3.1415927f / 180f
        if (carry != null) player.copyInventoryFrom(carry)
        if (player.health <= 0) player.health = 100
        var monsterIndex = 0
        for (t in level.things) {
            val et = Enemies.forChar(t.ch)
            when {
                et >= 0 -> {
                    val type = Enemies.types[et]
                    if (et != Enemies.BARREL && et != Enemies.BOSS) {
                        val skip = difficulty == 0 && monsterIndex % 3 == 2
                        monsterIndex++
                        if (skip) continue
                    }
                    val e = Enemy(type, t.x, t.y, t.z)
                    e.yaw = rng.range(-3.14f, 3.14f)
                    e.cooldown = rng.range(0.5f, 2f)
                    if (type.flying) { e.z = t.z + 0.9f; e.hoverZ = e.z }
                    e.counted = type.countsAsKill
                    if (e.counted) killsTotal++
                    if (et == Enemies.BOSS) { boss = e; e.yaw = -1.5708f }
                    enemies.add(e)
                }
                t.ch == 'l' -> props.add(Prop(t.x, t.y, t.z, 0.22f))
                Pickups.isPickup(t.ch) -> pickups.add(Pickup(t.ch, t.x, t.y, t.z))
            }
        }
    }

    // ------------------------------------------------------------------ mise à jour

    fun update(dt: Float, input: Input) {
        time += dt
        if (completeTimer >= 0f) {
            completeTimer -= dt
            if (completeTimer < 0f) completed = true
        }
        painSoundTimer -= dt; lockedMsgTimer -= dt
        updatePlayer(dt, input)
        updateDoors(dt)
        flowTimer -= dt
        if (flowTimer <= 0f) { flowTimer = 0.3f; computeFlow() }
        noiseTimer -= dt
        for (i in enemies.indices) updateEnemy(enemies[i], dt)
        separateEnemies()
        updateProjectiles(dt)
        updateBooms(dt)
        updateParticles(dt)
        updateLights(dt)
        seenTimer -= dt
        if (seenTimer <= 0f) { seenTimer = 0.25f; markSeen() }
        if (bossDead) {
            bossDeathTime += dt
            if (completeTimer < 0f && bossDeathTime > 3.5f && !completed) completeTimer = 0.01f
        }
    }

    private fun updatePlayer(dt: Float, input: Input) {
        val p = player
        p.hurtFlash = max(0f, p.hurtFlash - dt * 1.8f)
        p.pickupFlash = max(0f, p.pickupFlash - dt * 3f)
        p.hurtDirTime = max(0f, p.hurtDirTime - dt)
        p.flash = max(0f, p.flash - dt)
        p.fireAnim = max(0f, p.fireAnim - dt * 4f)
        p.cooldown -= dt
        if (p.dead) {
            p.deadTime += dt
            val target = p.z + 0.12f
            p.eyeZ += (target - p.eyeZ) * min(1f, dt * 4f)
            p.pitch += (0.35f - p.pitch) * min(1f, dt * 2f)
            return
        }
        p.yaw = wrapAngle(p.yaw + input.lookYaw)
        p.pitch = clamp(p.pitch + input.lookPitch, -0.8f, 0.8f)

        // Déplacement
        val fx = cos(p.yaw); val fy = sin(p.yaw)
        var wx = fx * input.moveY + fy * input.moveX
        var wy = fy * input.moveY - fx * input.moveX
        val wl = sqrt(wx * wx + wy * wy)
        if (wl > 1f) { wx /= wl; wy /= wl }
        val k = min(1f, dt * 11f)
        p.vx += (wx * SPEED - p.vx) * k
        p.vy += (wy * SPEED - p.vy) * k
        val mask = phys.move(p.x, p.y, p.vx * dt, p.vy * dt, PLAYER_R, p.z, PLAYER_H, false, tmp2)
        var nx = tmp2[0]; var ny = tmp2[1]
        if (mask and 1 != 0) p.vx *= 0.5f
        if (mask and 2 != 0) p.vy *= 0.5f
        // Ne pas traverser les ennemis ni les colonnes
        for (e in enemies) {
            if (!e.alive || e.type.flying) continue
            if (e.z > p.z + PLAYER_H || e.z + e.type.height < p.z) continue
            val dx = nx - e.x; val dy = ny - e.y
            val rr = PLAYER_R + e.type.radius
            val d2 = dx * dx + dy * dy
            if (d2 < rr * rr && d2 > 1e-6f) {
                val d = sqrt(d2)
                val px = e.x + dx / d * rr; val py = e.y + dy / d * rr
                if (!phys.blocked(px, py, PLAYER_R, p.z, PLAYER_H, false)) { nx = px; ny = py } else { nx = p.x; ny = p.y }
            }
        }
        for (pr in props) {
            val dx = nx - pr.x; val dy = ny - pr.y
            val rr = PLAYER_R + pr.radius
            val d2 = dx * dx + dy * dy
            if (d2 < rr * rr && d2 > 1e-6f) {
                val d = sqrt(d2)
                val px = pr.x + dx / d * rr; val py = pr.y + dy / d * rr
                if (!phys.blocked(px, py, PLAYER_R, p.z, PLAYER_H, false)) { nx = px; ny = py } else { nx = p.x; ny = p.y }
            }
        }
        val moved = sqrt((nx - p.x) * (nx - p.x) + (ny - p.y) * (ny - p.y))
        p.x = nx; p.y = ny

        // Vertical
        val ground = phys.groundUnder(p.x, p.y, PLAYER_R, p.z)
        if (ground >= p.z - 0.001f) {
            p.z = ground; p.vz = 0f
        } else {
            p.vz -= GRAVITY * dt
            p.z += p.vz * dt
            if (p.z <= ground) {
                if (p.vz < -6f) { ev.sound(Sfx.PUNCH, p.x, p.y, 0.4f); ev.vibrate(30, 0.3f) }
                p.z = ground; p.vz = 0f
            }
        }
        val targetEye = p.z + EYE
        p.eyeZ += (targetEye - p.eyeZ) * min(1f, dt * if (targetEye > p.eyeZ) 14f else 20f)

        // Balancement
        val speedFrac = clamp(moved / dt / SPEED, 0f, 1f)
        p.bobAmount += (speedFrac - p.bobAmount) * min(1f, dt * 8f)
        p.bobPhase += dt * 9.5f * speedFrac

        interact()
        lavaCheck(dt)
        collectPickups()
        weapons(dt, input)
    }

    private fun lavaCheck(dt: Float) {
        val p = player
        val c = level.cellAt(p.x, p.y) ?: return
        if (c.lava && p.z <= c.floor + 0.02f) {
            p.lavaTimer -= dt
            if (p.lavaTimer <= 0f) {
                p.lavaTimer = 0.5f
                hurtPlayer(6, p.x, p.y, armorProtects = false, fromLava = true)
                ev.sound(Sfx.LAVA, p.x, p.y, 0.7f)
                for (i in 0 until 4) glows.spawn(p.x + rng.signed() * 0.3f, p.y + rng.signed() * 0.3f, p.z + 0.05f, 0f, 0f, rng.range(0.4f, 1f), 0.5f, 0.12f, 1f, 0.6f, 0.1f)
            }
        } else p.lavaTimer = 0f
    }

    /** Portes, passages secrets et interrupteur de sortie : il suffit de s'en approcher. */
    private fun interact() {
        val p = player
        val reach = PLAYER_R + 0.38f
        val fx = cos(p.yaw); val fy = sin(p.yaw)
        val x0 = kotlin.math.floor(p.x - reach).toInt(); val x1 = kotlin.math.floor(p.x + reach).toInt()
        val y0 = kotlin.math.floor(p.y - reach).toInt(); val y1 = kotlin.math.floor(p.y + reach).toInt()
        for (cy in y0..y1) for (cx in x0..x1) {
            val c = level.cell(cx, cy) ?: continue
            val px = p.x.coerceIn(cx.toFloat(), cx + 1f); val py = p.y.coerceIn(cy.toFloat(), cy + 1f)
            val dx = px - p.x; val dy = py - p.y
            val d = sqrt(dx * dx + dy * dy)
            if (d > reach) continue
            val facing = if (d < 1e-3f) 1f else (dx * fx + dy * fy) / d
            val d0 = c.door
            if (d0 != null && (d0.state == Door.CLOSED || d0.state == Door.CLOSING)) {
                if (d0.secret) {
                    if (facing > 0.55f) openDoor(d0, true)
                } else if (d0.key >= 0 && !p.keys[d0.key]) {
                    if (facing > 0.4f && lockedMsgTimer <= 0f) {
                        lockedMsgTimer = 2f
                        ev.message("Carte d'accès ${KEY_NAMES[d0.key]} requise")
                        ev.sound(Sfx.DENIED, cx + 0.5f, cy + 0.5f)
                    }
                } else openDoor(d0, true)
            }
            if (c.exit && facing > 0.3f && d < PLAYER_R + 0.25f && completeTimer < 0f && !completed) {
                ev.sound(Sfx.EXIT, cx + 0.5f, cy + 0.5f)
                ev.vibrate(120, 0.6f)
                completeTimer = 0.6f
            }
        }
    }

    fun openDoor(d: Door, byPlayer: Boolean) {
        if (d.state == Door.OPEN || d.state == Door.OPENING) return
        d.state = Door.OPENING
        ev.sound(Sfx.DOOR_OPEN, d.cx + 0.5f, d.cy + 0.5f)
        if (d.secret && d.open == 0f && byPlayer) {
            secretsFound++
            ev.message("Passage secret découvert !")
            ev.soundUi(Sfx.SECRET)
        }
    }

    private fun updateDoors(dt: Float) {
        for (d in level.doors) {
            when (d.state) {
                Door.OPENING -> {
                    d.open += dt * 1.3f
                    if (d.open >= 1f) { d.open = 1f; d.state = Door.OPEN; d.timer = 4f }
                }
                Door.OPEN -> {
                    if (d.secret) continue
                    d.timer -= dt
                    if (d.timer <= 0f) {
                        if (occupied(d)) d.timer = 1f else { d.state = Door.CLOSING; ev.sound(Sfx.DOOR_CLOSE, d.cx + 0.5f, d.cy + 0.5f) }
                    }
                }
                Door.CLOSING -> {
                    if (occupied(d)) { d.state = Door.OPENING; continue }
                    d.open -= dt * 1.3f
                    if (d.open <= 0f) { d.open = 0f; d.state = Door.CLOSED }
                }
            }
        }
    }

    private fun occupied(d: Door): Boolean {
        val x0 = d.cx - 0.05f; val x1 = d.cx + 1.05f; val y0 = d.cy - 0.05f; val y1 = d.cy + 1.05f
        fun inside(x: Float, y: Float, r: Float) = x + r > x0 && x - r < x1 && y + r > y0 && y - r < y1
        if (!player.dead && inside(player.x, player.y, PLAYER_R)) return true
        for (e in enemies) if (e.alive && inside(e.x, e.y, e.type.radius)) return true
        return false
    }

    // ------------------------------------------------------------------ objets

    private fun collectPickups() {
        val p = player
        for (pk in pickups) {
            if (pk.taken) continue
            val dx = pk.x - p.x; val dy = pk.y - p.y
            if (dx * dx + dy * dy > 0.42f) continue
            if (abs(pk.z - p.z) > 0.7f) continue
            val msg = take(pk.ch) ?: continue
            pk.taken = true
            p.pickupFlash = 1f
            ev.message(msg)
            val snd = when (pk.ch) { 'S', 'G', 'K', 'U' -> Sfx.PICKUP_WEAPON; 'r', 'u', 'y' -> Sfx.PICKUP_KEY; else -> Sfx.PICKUP }
            ev.soundUi(snd)
            ev.vibrate(25, 0.25f)
        }
    }

    private fun addAmmo(type: Int, amount: Int): Boolean {
        val p = player
        if (p.ammo[type] >= Ammo.MAX[type]) return false
        val a = (amount * Difficulty.AMMO_PICKUP[difficulty]).toInt()
        p.ammo[type] = min(Ammo.MAX[type], p.ammo[type] + a)
        return true
    }

    private fun giveWeapon(w: Int, ammoType: Int, amount: Int): Boolean {
        val p = player
        val isNew = !p.owned[w]
        val gotAmmo = addAmmo(ammoType, amount)
        if (!isNew && !gotAmmo) return false
        if (isNew) { p.owned[w] = true; selectWeapon(w) }
        return true
    }

    /** Applique un objet ; renvoie le message ou null si l'objet n'est pas utile. */
    fun take(ch: Char): String? {
        val p = player
        return when (ch) {
            'h' -> if (p.health < 100) { p.health = min(100, p.health + 15); "Petite trousse de soins" } else null
            'H' -> if (p.health < 100) { p.health = min(100, p.health + 40); "Trousse de soins" } else null
            'a' -> if (p.armor < 100) { p.armor = 100; p.armorHeavy = false; "Gilet pare-balles" } else null
            'A' -> if (p.armor < 200) { p.armor = 200; p.armorHeavy = true; "Exo-armure !" } else null
            'm' -> if (addAmmo(Ammo.BULLETS, 20)) "Chargeur de balles" else null
            'q' -> if (addAmmo(Ammo.BULLETS, 10)) "Chargeur entamé" else null
            'n' -> if (addAmmo(Ammo.SHELLS, 6)) "Boîte de cartouches" else null
            'o' -> if (addAmmo(Ammo.ROCKETS, 2)) "Roquettes" else null
            'p' -> if (addAmmo(Ammo.CELLS, 40)) "Cellules d'énergie" else null
            'S' -> if (giveWeapon(Weapons.SHOTGUN, Ammo.SHELLS, 8)) "Fusil à pompe !" else null
            'G' -> if (giveWeapon(Weapons.CHAINGUN, Ammo.BULLETS, 40)) "Mitrailleuse rotative !" else null
            'K' -> if (giveWeapon(Weapons.ROCKET, Ammo.ROCKETS, 4)) "Lance-roquettes !" else null
            'U' -> if (giveWeapon(Weapons.ANNIHILATOR, Ammo.CELLS, 80)) "ANNIHILATEUR !" else null
            'r' -> { p.keys[0] = true; "Carte d'accès rouge" }
            'u' -> { p.keys[1] = true; "Carte d'accès bleue" }
            'y' -> { p.keys[2] = true; "Carte d'accès jaune" }
            else -> null
        }
    }

    // ------------------------------------------------------------------ armes

    fun hasAmmoFor(w: Int): Boolean {
        val d = Weapons.defs[w]
        return d.ammo == Ammo.NONE || player.ammo[d.ammo] >= d.perShot
    }

    fun selectWeapon(w: Int) {
        val p = player
        if (!p.owned[w] || (w == p.weapon && p.pendingWeapon < 0)) return
        p.pendingWeapon = w
        p.chargeTime = 0f
    }

    private fun bestWeapon(): Int {
        val order = intArrayOf(Weapons.CHAINGUN, Weapons.SHOTGUN, Weapons.ROCKET, Weapons.PISTOL, Weapons.ANNIHILATOR, Weapons.FIST)
        for (w in order) if (player.owned[w] && hasAmmoFor(w)) return w
        return Weapons.FIST
    }

    private fun weapons(dt: Float, input: Input) {
        val p = player
        if (input.select in 0 until Weapons.COUNT) {
            if (p.owned[input.select]) selectWeapon(input.select)
        }
        if (input.next) {
            for (i in 1..Weapons.COUNT) {
                val w = (p.weapon + i) % Weapons.COUNT
                if (p.owned[w] && hasAmmoFor(w)) { selectWeapon(w); break }
            }
        }
        if (p.pendingWeapon >= 0) {
            p.switchAnim += dt * 7f
            if (p.switchAnim >= 1f) { p.switchAnim = 1f; p.weapon = p.pendingWeapon; p.pendingWeapon = -1 }
            return
        }
        if (p.switchAnim > 0f) { p.switchAnim = max(0f, p.switchAnim - dt * 7f); return }
        if (p.weapon == Weapons.CHAINGUN) p.spin = if (input.fire) min(1f, p.spin + dt * 5f) else max(0f, p.spin - dt * 2f)

        // Annihilateur : charge avant le tir
        if (p.chargeTime > 0f) {
            p.chargeTime -= dt
            if (p.chargeTime <= 0f) { p.chargeTime = 0f; fireNow() }
            return
        }
        if (!input.fire || p.cooldown > 0f) return
        if (!hasAmmoFor(p.weapon)) {
            ev.soundUi(Sfx.NO_AMMO, 0.6f)
            p.cooldown = 0.4f
            selectWeapon(bestWeapon())
            return
        }
        if (p.weapon == Weapons.ANNIHILATOR) {
            p.chargeTime = 0.35f
            p.cooldown = Weapons.defs[p.weapon].cooldown
            ev.sound(Sfx.ANNIHIL_CHARGE, p.x, p.y)
            return
        }
        fireNow()
    }

    private var aimYaw = 0f
    private var aimPitch = 0f

    private fun computeAim(range: Float) {
        val p = player
        aimYaw = p.yaw; aimPitch = p.pitch
        if (!aimAssist) return
        var best: Enemy? = null; var bestDiff = 0.13f
        for (e in enemies) {
            if (!e.alive || e.type.id == Enemies.BARREL) continue
            val dx = e.x - p.x; val dy = e.y - p.y
            val d = sqrt(dx * dx + dy * dy)
            if (d > range || d < 0.05f) continue
            val diff = abs(wrapAngle(atan2(dy, dx) - p.yaw))
            val tol = bestDiff.coerceAtLeast(0f) + if (d < 2f) 0.15f else 0f
            if (diff > tol) continue
            val ez = e.z + e.type.height * 0.6f
            if (!phys.lineOfSight(p.x, p.y, p.eyeZ, e.x, e.y, ez)) continue
            best = e; bestDiff = diff
        }
        val b = best ?: return
        val dx = b.x - p.x; val dy = b.y - p.y
        val d = sqrt(dx * dx + dy * dy)
        aimPitch = atan2(b.z + b.type.height * 0.6f - p.eyeZ, d)
        val dyaw = wrapAngle(atan2(dy, dx) - p.yaw)
        aimYaw = p.yaw + clamp(dyaw, -0.04f, 0.04f)
    }

    private fun fireNow() {
        val p = player
        val wd = Weapons.defs[p.weapon]
        if (!hasAmmoFor(p.weapon)) return
        if (wd.ammo != Ammo.NONE) p.ammo[wd.ammo] -= wd.perShot
        p.cooldown = wd.cooldown
        p.fireAnim = 1f
        when (p.weapon) {
            Weapons.FIST -> { melee(); return }
            Weapons.PISTOL -> ev.sound(Sfx.PISTOL, p.x, p.y)
            Weapons.SHOTGUN -> ev.sound(Sfx.SHOTGUN, p.x, p.y)
            Weapons.CHAINGUN -> ev.sound(Sfx.CHAINGUN, p.x, p.y, 0.8f)
            Weapons.ROCKET -> ev.sound(Sfx.ROCKET_FIRE, p.x, p.y)
            Weapons.ANNIHILATOR -> ev.sound(Sfx.ANNIHIL_FIRE, p.x, p.y)
        }
        ev.vibrate(when (p.weapon) { Weapons.SHOTGUN -> 45; Weapons.ROCKET, Weapons.ANNIHILATOR -> 60; Weapons.CHAINGUN -> 14; else -> 22 },
            when (p.weapon) { Weapons.SHOTGUN, Weapons.ROCKET, Weapons.ANNIHILATOR -> 0.8f; else -> 0.4f })
        p.flash = 0.07f
        val fl = if (p.weapon == Weapons.ANNIHILATOR) floatArrayOf(0.4f, 1f, 0.5f) else floatArrayOf(1f, 0.75f, 0.35f)
        lights.add(DynLight(p.x + cos(p.yaw) * 0.6f, p.y + sin(p.yaw) * 0.6f, p.eyeZ, 4.5f, fl[0] * 1.4f, fl[1] * 1.4f, fl[2] * 1.4f, 0.08f))
        makeNoise()
        computeAim(40f)
        if (wd.projectile >= 0) {
            val cp = cos(aimPitch); val dx = cos(aimYaw) * cp; val dy = sin(aimYaw) * cp; val dz = sin(aimPitch)
            val sx = p.x + dx * 0.3f + sin(p.yaw) * 0.08f
            val sy = p.y + dy * 0.3f - cos(p.yaw) * 0.08f
            val sz = p.eyeZ - 0.1f + dz * 0.3f
            if (wd.projectile == Projectile.ROCKET) {
                projectiles.add(Projectile(Projectile.ROCKET, sx, sy, sz, dx * 16f, dy * 16f, dz * 16f, true, rng.range(wd.dmgMin.toFloat(), wd.dmgMax.toFloat()).toInt(), 110, 2.6f))
            } else {
                projectiles.add(Projectile(Projectile.ANNIHIL, sx, sy, sz, dx * 11f, dy * 11f, dz * 11f, true, wd.dmgMin, 320, 4.5f))
            }
            return
        }
        for (i in 0 until wd.pellets) {
            val yaw = aimYaw + rng.signed() * wd.spreadH
            val pitch = aimPitch + rng.signed() * wd.spreadV
            hitscan(p.x, p.y, p.eyeZ, yaw, pitch, rng.range(wd.dmgMin.toFloat(), wd.dmgMax + 0.99f).toInt(), true)
        }
    }

    private fun melee() {
        val p = player
        var best: Enemy? = null; var bd = 1.25f
        for (e in enemies) {
            if (!e.alive) continue
            val dx = e.x - p.x; val dy = e.y - p.y
            val d = sqrt(dx * dx + dy * dy) - e.type.radius
            if (d > bd) continue
            if (abs(wrapAngle(atan2(dy, dx) - p.yaw)) > 0.6f) continue
            if (e.z > p.eyeZ + 0.3f || e.z + e.type.height < p.z) continue
            best = e; bd = d
        }
        if (best != null) {
            val wd = Weapons.defs[Weapons.FIST]
            hurtEnemy(best, rng.range(wd.dmgMin.toFloat(), wd.dmgMax.toFloat()).toInt(), p.x, p.y, true, 1.5f)
            ev.sound(Sfx.PUNCH, p.x, p.y)
            ev.vibrate(35, 0.6f)
            blood(best, best.x, best.y, best.z + best.type.height * 0.6f, 6)
        } else ev.sound(Sfx.WHOOSH, p.x, p.y, 0.6f)
    }

    /** Tir instantané : renvoie true si un ennemi est touché. */
    private fun hitscan(ox: Float, oy: Float, oz: Float, yaw: Float, pitch: Float, dmg: Int, fromPlayer: Boolean): Boolean {
        val cp = cos(pitch)
        val dx = cos(yaw) * cp; val dy = sin(yaw) * cp; val dz = sin(pitch)
        phys.trace(ox, oy, oz, dx, dy, dz, 60f, hit)
        val wallT = hit.t
        var bestT = wallT; var target: Enemy? = null
        for (e in enemies) {
            if (!e.alive) continue
            val t = phys.rayCylinder(ox, oy, oz, dx, dy, dz, e.x, e.y, e.z, e.type.radius, e.type.height)
            if (t in 0f..bestT) { bestT = t; target = e }
        }
        val hx = ox + dx * bestT; val hy = oy + dy * bestT; val hz = oz + dz * bestT
        if (target != null) {
            hurtEnemy(target, dmg, ox, oy, fromPlayer, 0.3f)
            blood(target, hx, hy, hz, 3)
            return true
        }
        if (hit.kind == Hit.WALL || hit.kind == Hit.FLOOR || hit.kind == Hit.CEIL) {
            val bx = hx + hit.nx * 0.02f; val by = hy + hit.ny * 0.02f; val bz = hz + hit.nz * 0.02f
            for (i in 0 until 3) glows.spawn(bx, by, bz, hit.nx * 1.5f + rng.signed(), hit.ny * 1.5f + rng.signed(), hit.nz * 1.5f + rng.range(0f, 1.5f), 0.18f, 0.05f, 1f, 0.8f, 0.4f, 6f)
            debris.spawn(bx, by, bz, hit.nx + rng.signed() * 0.8f, hit.ny + rng.signed() * 0.8f, rng.range(0.5f, 2f), 0.6f, 0.025f, 0.35f, 0.35f, 0.35f, 9f)
            if (rng.chance(0.25f)) ev.sound(Sfx.RICOCHET, hx, hy, 0.35f)
        }
        return false
    }

    private fun makeNoise() {
        if (noiseTimer > 0f) return
        noiseTimer = 0.4f
        // Propagation du bruit à travers les portes ouvertes
        val w = level.w; val h = level.h
        val dist = IntArray(w * h) { -1 }
        val start = cellIndex(player.x, player.y)
        if (start < 0) return
        val q = IntArray(w * h); var qh = 0; var qt = 0
        dist[start] = 0; q[qt++] = start
        while (qh < qt) {
            val i = q[qh++]
            if (dist[i] >= 18) continue
            val cx = i % w; val cy = i / w
            for ((ddx, ddy) in Level.NEIGH4) {
                val nx = cx + ddx; val ny = cy + ddy
                val n = level.cell(nx, ny) ?: continue
                val ni = ny * w + nx
                if (n.solid || dist[ni] >= 0) continue
                val dr = n.door
                if (dr != null && dr.open < 0.5f) continue
                dist[ni] = dist[i] + 1; q[qt++] = ni
            }
        }
        for (e in enemies) {
            if (e.state != Enemy.IDLE || e.type.id == Enemies.BARREL) continue
            val i = cellIndex(e.x, e.y)
            if (i >= 0 && dist[i] >= 0) alert(e)
        }
    }

    // ------------------------------------------------------------------ dégâts

    fun hurtPlayer(amount: Int, fromX: Float, fromY: Float, armorProtects: Boolean = true, fromLava: Boolean = false) {
        val p = player
        if (p.dead || completeTimer >= 0f) return
        var dmg = max(1, (amount * Difficulty.DAMAGE_TAKEN[difficulty]).toInt())
        if (armorProtects && p.armor > 0) {
            var absorb = if (p.armorHeavy) dmg / 2 else dmg / 3
            absorb = min(absorb, p.armor)
            p.armor -= absorb
            if (p.armor == 0) p.armorHeavy = false
            dmg -= absorb
        }
        p.health -= dmg
        p.hurtFlash = min(1f, p.hurtFlash + 0.25f + dmg / 40f)
        if (!fromLava) {
            p.hurtDirTime = 1f
            p.hurtDirAngle = atan2(fromY - p.y, fromX - p.x)
        }
        ev.vibrate(40 + min(80, dmg * 3), min(1f, 0.35f + dmg / 40f))
        ev.shake(min(0.6f, dmg / 40f))
        if (p.health <= 0) {
            p.health = 0
            p.dead = true
            ev.soundUi(Sfx.PLAYER_DEATH)
            ev.vibrate(400, 1f)
        } else if (painSoundTimer <= 0f) {
            painSoundTimer = 0.35f
            ev.soundUi(Sfx.PLAYER_PAIN)
        }
    }

    fun hurtEnemy(e: Enemy, dmg: Int, fromX: Float, fromY: Float, fromPlayer: Boolean, knock: Float) {
        if (!e.alive) return
        e.hp -= dmg
        e.hitFlash = 0.12f
        if (e.type.id != Enemies.BARREL && e.type.id != Enemies.BOSS && knock > 0f) {
            val dx = e.x - fromX; val dy = e.y - fromY
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
            val push = knock * 60f / (e.type.hp + 40f)
            phys.move(e.x, e.y, dx / d * push, dy / d * push, e.type.radius, e.z, e.type.height, e.type.flying, tmp2)
            e.x = tmp2[0]; e.y = tmp2[1]
        }
        if (e.hp <= 0) { kill(e, fromPlayer); return }
        if (e.type.id == Enemies.BARREL) return
        if (e.state == Enemy.IDLE) alert(e)
        if (rng.chance(e.type.painChance) && e.state != Enemy.CHARGE) {
            e.state = Enemy.PAIN; e.stateTime = 0f
            ev.sound(Sfx.PAIN, e.x, e.y, 0.7f)
        }
    }

    private fun kill(e: Enemy, fromPlayer: Boolean) {
        e.state = Enemy.DEAD
        e.deathTime = 0f
        e.hp = 0
        if (e.counted && !e.summoned) kills++
        val tid = e.type.id
        when (tid) {
            Enemies.BARREL -> {
                booms.add(PendingBoom(e.x, e.y, e.z + 0.3f, 2.8f, 120, fromPlayer, true, 0.12f, false))
            }
            Enemies.BOSS -> {
                bossDead = true
                ev.sound(Sfx.BOSS_ROAR, e.x, e.y, 1f)
                ev.message("L'HÔTE EST DÉTRUIT !")
                for (i in 0 until 9) booms.add(PendingBoom(e.x + rng.signed() * 1.2f, e.y + rng.signed() * 1.2f, e.z + rng.range(0.3f, 2f), 1.5f, 0, true, false, 0.2f + i * 0.3f, true))
            }
            else -> {
                ev.sound(if (tid == Enemies.BRUTE) Sfx.BIG_DEATH else Sfx.DEATH, e.x, e.y)
                gibs(e, if (tid == Enemies.BRUTE) 16 else 9)
                if (tid == Enemies.MINER && rng.chance(0.6f)) pickups.add(Pickup('q', e.x, e.y, phys.groundUnder(e.x, e.y, 0.1f, e.z + 0.3f)))
                if (tid == Enemies.DRONE) booms.add(PendingBoom(e.x, e.y, e.z + 0.2f, 1.2f, 0, fromPlayer, false, 0.05f, false))
            }
        }
    }

    private fun bloodColor(e: Enemy): FloatArray = when (e.type.id) {
        Enemies.DRONE, Enemies.BARREL -> BLOOD_SPARK
        Enemies.SPITTER -> BLOOD_GREEN
        else -> BLOOD_RED
    }

    private fun blood(e: Enemy, x: Float, y: Float, z: Float, n: Int) {
        val c = bloodColor(e)
        for (i in 0 until n) debris.spawn(x, y, z, rng.signed() * 1.5f, rng.signed() * 1.5f, rng.range(0.5f, 2.5f), 0.8f, rng.range(0.025f, 0.05f), c[0], c[1], c[2], 9f)
        if (e.type.id == Enemies.DRONE) for (i in 0 until 3) glows.spawn(x, y, z, rng.signed() * 2f, rng.signed() * 2f, rng.range(0f, 2f), 0.2f, 0.05f, 1f, 0.7f, 0.3f, 6f)
    }

    private fun gibs(e: Enemy, n: Int) {
        val c = bloodColor(e)
        for (i in 0 until n) {
            val z = e.z + rng.range(0.1f, e.type.height)
            debris.spawn(e.x, e.y, z, rng.signed() * 2.5f, rng.signed() * 2.5f, rng.range(1f, 4f), rng.range(1.5f, 3f), rng.range(0.04f, 0.09f), c[0] * rng.range(0.7f, 1f), c[1], c[2], 9f)
        }
    }

    private fun explode(b: PendingBoom) {
        val p = player
        ev.sound(Sfx.EXPLOSION, b.x, b.y, if (b.big) 1f else 0.9f)
        val dist = sqrt((p.x - b.x) * (p.x - b.x) + (p.y - b.y) * (p.y - b.y))
        ev.shake(clamp(1.2f - dist * 0.12f, 0f, 0.8f))
        if (dist < 6f) ev.vibrate(60 + (6f - dist).toInt() * 15, clamp(1f - dist / 6f, 0.2f, 1f))
        lights.add(DynLight(b.x, b.y, b.z + 0.3f, b.radius * 2.5f + 2f, 2.2f, 1.2f, 0.4f, 0.35f))
        val big = b.radius > 3f
        val nGlow = if (big) 40 else 22
        for (i in 0 until nGlow) {
            val a = rng.range(0f, 6.283f); val s = rng.range(0.5f, if (big) 5f else 3f)
            val (cr, cg, cb) = if (b.radius > 4f) Triple(0.4f, 1f, 0.5f) else Triple(1f, rng.range(0.4f, 0.8f), 0.15f)
            glows.spawn(b.x, b.y, b.z, cos(a) * s, sin(a) * s, rng.range(-1f, 3f), rng.range(0.3f, 0.7f), rng.range(0.15f, 0.35f), cr, cg, cb, -1f, rng.range(0.3f, 1.2f))
        }
        for (i in 0 until 8) debris.spawn(b.x, b.y, b.z, rng.signed() * 4f, rng.signed() * 4f, rng.range(1f, 5f), rng.range(1f, 2f), rng.range(0.03f, 0.07f), 0.25f, 0.22f, 0.2f, 9f)
        if (b.damage <= 0) return
        // Dégâts de zone avec ligne de vue
        if (b.hurtsPlayer && !p.dead) {
            val dz = when { p.z > b.z -> p.z - b.z; p.z + PLAYER_H < b.z -> b.z - (p.z + PLAYER_H); else -> 0f }
            val d = sqrt(dist * dist + dz * dz)
            if (d < b.radius && phys.lineOfSight(b.x, b.y, b.z, p.x, p.y, p.z + PLAYER_H * 0.5f)) {
                hurtPlayer(((1f - d / b.radius) * b.damage * (if (b.fromPlayer) 0.7f else 1f)).toInt().coerceAtLeast(1), b.x, b.y)
            }
        }
        for (e in enemies) {
            if (!e.alive) continue
            val dx = e.x - b.x; val dy = e.y - b.y
            val dz = when { e.z > b.z -> e.z - b.z; e.z + e.type.height < b.z -> b.z - (e.z + e.type.height); else -> 0f }
            val d = (sqrt(dx * dx + dy * dy) - e.type.radius).coerceAtLeast(0f).let { sqrt(it * it + dz * dz) }
            if (d >= b.radius) continue
            if (!phys.lineOfSight(b.x, b.y, b.z, e.x, e.y, e.z + e.type.height * 0.5f)) continue
            hurtEnemy(e, ((1f - d / b.radius) * b.damage).toInt().coerceAtLeast(1), b.x, b.y, b.fromPlayer, 1.5f)
        }
    }

    private fun updateBooms(dt: Float) {
        var i = 0
        while (i < booms.size) {
            val b = booms[i]
            b.timer -= dt
            if (b.timer <= 0f) { booms.removeAt(i); explode(b) } else i++
        }
    }

    // ------------------------------------------------------------------ IA

    private fun alert(e: Enemy) {
        if (e.type.id == Enemies.BARREL || !e.alive) return
        e.state = Enemy.CHASE
        e.stateTime = 0f
        val snd = when (e.type.id) {
            Enemies.MINER -> Sfx.SIGHT_MINER; Enemies.CRAWLER -> Sfx.SIGHT_CRAWLER; Enemies.SPITTER -> Sfx.SIGHT_SPITTER
            Enemies.BRUTE -> Sfx.SIGHT_BRUTE; Enemies.DRONE -> Sfx.SIGHT_DRONE; else -> Sfx.BOSS_ROAR
        }
        ev.sound(snd, e.x, e.y, 0.9f)
        if (e.type.id == Enemies.BOSS) { ev.shake(0.6f); ev.vibrate(300, 0.8f); ev.message("L'HÔTE S'ÉVEILLE...") }
    }

    private fun eyeZ(e: Enemy) = e.z + e.type.height * 0.8f

    private fun canSee(e: Enemy, d: Float): Boolean {
        val p = player
        if (p.dead || d > 26f) return false
        val ang = abs(wrapAngle(atan2(p.y - e.y, p.x - e.x) - e.yaw))
        if (ang > 1.75f && d > 4f) return false
        return phys.lineOfSight(e.x, e.y, eyeZ(e), p.x, p.y, p.eyeZ)
    }

    private fun updateEnemy(e: Enemy, dt: Float) {
        e.hitFlash -= dt
        if (!e.alive) { e.deathTime += dt; return }
        if (e.type.id == Enemies.BARREL) return
        val p = player
        e.stateTime += dt
        e.cooldown -= dt
        val dx = p.x - e.x; val dy = p.y - e.y
        val dist = sqrt(dx * dx + dy * dy)
        val toPlayer = atan2(dy, dx)
        if (e.type.flying) {
            val c = level.cellAt(e.x, e.y)
            val floor = c?.floor ?: 0f
            val top = c?.let { level.effCeil(it) } ?: 2f
            val target = clamp(max(floor, p.z) + 0.85f + sin(time * 2f + e.x) * 0.12f, floor + 0.1f, top - e.type.height - 0.05f)
            e.z += (target - e.z) * min(1f, dt * 2f)
        }
        when (e.state) {
            Enemy.IDLE -> {
                e.sightTimer -= dt
                if (e.sightTimer <= 0f) {
                    e.sightTimer = 0.25f + rng.float() * 0.2f
                    if (canSee(e, dist)) alert(e)
                }
            }
            Enemy.PAIN -> if (e.stateTime > 0.3f) { e.state = Enemy.CHASE; e.stateTime = 0f }
            Enemy.WINDUP -> {
                e.yaw = turnTo(e.yaw, toPlayer, dt * 8f)
                if (e.stateTime >= e.type.windup * (if (e.type.id == Enemies.BOSS && e.attackKind == 2) 1.6f else 1f)) {
                    attack(e, dist)
                    e.state = Enemy.CHASE; e.stateTime = 0f
                    e.cooldown = rng.range(e.type.cooldownMin, e.type.cooldownMax) * Difficulty.COOLDOWN[difficulty] *
                        (if (e.type.id == Enemies.BOSS && e.hp < e.type.hp / 2) 0.65f else 1f)
                }
            }
            Enemy.CHARGE -> {
                val sp = e.type.speed * 2.6f
                val cx = cos(e.yaw) * sp * dt; val cy = sin(e.yaw) * sp * dt
                val mask = phys.move(e.x, e.y, cx, cy, e.type.radius, e.z, e.type.height, false, tmp2)
                e.x = tmp2[0]; e.y = tmp2[1]; e.z = phys.groundUnder(e.x, e.y, e.type.radius, e.z)
                e.walkPhase += dt * 14f
                if (dist < e.type.radius + PLAYER_R + 0.25f && !p.dead) {
                    hurtPlayer(rng.range(e.type.meleeMin.toFloat(), e.type.meleeMax.toFloat()).toInt(), e.x, e.y)
                    ev.sound(Sfx.MELEE_HIT, e.x, e.y)
                    // Projection du joueur
                    val kx = cos(e.yaw) * 0.6f; val ky = sin(e.yaw) * 0.6f
                    phys.move(p.x, p.y, kx, ky, PLAYER_R, p.z, PLAYER_H, false, tmp2)
                    p.x = tmp2[0]; p.y = tmp2[1]
                    e.state = Enemy.CHASE; e.stateTime = 0f; e.cooldown = 1.2f
                } else if (mask != 0 || e.stateTime > 1.3f) {
                    e.state = Enemy.CHASE; e.stateTime = 0f; e.cooldown = 0.8f
                }
            }
            Enemy.CHASE -> chase(e, dt, dist, toPlayer)
        }
    }

    private fun chase(e: Enemy, dt: Float, dist: Float, toPlayer: Float) {
        val p = player
        val t = e.type
        if (p.dead) { e.walkPhase += dt * 3f; return }
        // Boss : invocations périodiques
        if (t.id == Enemies.BOSS) {
            e.bossSummon -= dt
            if (e.bossSummon <= 0f && e.cooldown <= 0.5f) {
                e.bossSummon = rng.range(10f, 14f) * (if (e.hp < t.hp / 2) 0.6f else 1f)
                e.attackKind = 2; e.state = Enemy.WINDUP; e.stateTime = 0f
                ev.sound(Sfx.BOSS_ROAR, e.x, e.y)
                return
            }
        }
        if (e.cooldown <= 0f) {
            val vz = abs(p.z - e.z) < t.height + 0.3f
            if (dist < t.meleeRange + PLAYER_R && vz && t.meleeMax > 0) {
                e.attackKind = if (t.id == Enemies.BOSS) 3 else 0
                e.state = Enemy.WINDUP; e.stateTime = 0f
                ev.sound(Sfx.WHOOSH, e.x, e.y, 0.5f)
                return
            }
            if (t.id == Enemies.BRUTE && dist in 3f..9f && rng.chance(0.5f) && phys.lineOfSight(e.x, e.y, eyeZ(e), p.x, p.y, p.eyeZ)) {
                e.state = Enemy.CHARGE; e.stateTime = 0f; e.yaw = toPlayer
                ev.sound(Sfx.SIGHT_BRUTE, e.x, e.y, 1f)
                return
            }
            if (t.ranged != Enemies.R_NONE && dist < t.rangedRange && phys.lineOfSight(e.x, e.y, eyeZ(e), p.x, p.y, p.eyeZ)) {
                if (rng.chance(if (dist < 6f) 0.9f else 0.6f)) {
                    e.attackKind = if (t.id == Enemies.BOSS) (if (rng.chance(0.55f)) 0 else 1) else 1
                    e.state = Enemy.WINDUP; e.stateTime = 0f
                    return
                } else e.cooldown = 0.5f
            }
        }
        // Déplacement
        var mx: Float; var my: Float
        val los = dist < 7f && phys.lineOfSight(e.x, e.y, eyeZ(e), p.x, p.y, p.eyeZ)
        if (los) {
            mx = cos(toPlayer); my = sin(toPlayer)
            if (t.ranged != Enemies.R_NONE && t.id != Enemies.BOSS) {
                e.strafeTime -= dt
                if (e.strafeTime <= 0f) { e.strafeTime = rng.range(0.6f, 1.6f); e.strafeDir = if (rng.chance(0.5f)) 1f else -1f }
                val keep = if (dist < 3.5f) -0.6f else if (dist < 5f) 0f else 0.6f
                mx = mx * keep - my * e.strafeDir * 0.8f
                my = my * keep + cos(toPlayer) * e.strafeDir * 0.8f
            }
        } else {
            if (!flowDir(e)) { mx = 0f; my = 0f } else { mx = flowX; my = flowY }
        }
        if (e.wanderTime > 0f) { e.wanderTime -= dt; mx = e.wanderX; my = e.wanderY }
        val ml = sqrt(mx * mx + my * my)
        if (ml < 1e-3f) { e.yaw = turnTo(e.yaw, toPlayer, dt * 4f); return }
        mx /= ml; my /= ml
        if (dist < t.meleeRange * 0.8f + PLAYER_R && t.meleeMax > 0) { e.yaw = turnTo(e.yaw, toPlayer, dt * 6f); return }
        val sp = t.speed * (if (t.id == Enemies.BOSS && e.hp < t.hp / 2) 1.3f else 1f)
        val sx = mx * sp * dt; val sy = my * sp * dt
        phys.move(e.x, e.y, sx, sy, t.radius, e.z, t.height, t.flying, tmp2)
        var nx = tmp2[0]; var ny = tmp2[1]
        // Ne pas entrer dans le joueur
        val pdx = nx - p.x; val pdy = ny - p.y
        val rr = t.radius + PLAYER_R
        if (pdx * pdx + pdy * pdy < rr * rr && (t.flying.not() || abs(e.z - p.z) < 0.6f)) { nx = e.x; ny = e.y }
        val moved = sqrt((nx - e.x) * (nx - e.x) + (ny - e.y) * (ny - e.y))
        e.x = nx; e.y = ny
        if (!t.flying) e.z = phys.groundUnder(e.x, e.y, t.radius, e.z)
        e.walkPhase += moved * 6f
        e.yaw = turnTo(e.yaw, if (los) toPlayer else atan2(my, mx), dt * 6f)
        // Détection de blocage
        if (moved < sp * dt * 0.25f) {
            e.stuckTime += dt
            if (e.stuckTime > 0.5f) {
                e.stuckTime = 0f
                val a = rng.range(0f, 6.283f)
                e.wanderX = cos(a); e.wanderY = sin(a); e.wanderTime = 0.5f
            }
        } else e.stuckTime = 0f
        // Ouverture des portes sur le chemin
        val ahead = level.cellAt(e.x + mx * (t.radius + 0.3f), e.y + my * (t.radius + 0.3f))
        val d0 = ahead?.door
        if (d0 != null && !d0.secret && d0.key < 0 && (d0.state == Door.CLOSED || d0.state == Door.CLOSING)) openDoor(d0, false)
    }

    private fun attack(e: Enemy, dist: Float) {
        val p = player
        val t = e.type
        val ez = eyeZ(e)
        if (e.attackKind == 0 || e.attackKind == 3) {
            // Corps à corps
            if (dist < t.meleeRange + PLAYER_R + 0.35f && !p.dead) {
                hurtPlayer(rng.range(t.meleeMin.toFloat(), t.meleeMax.toFloat()).toInt(), e.x, e.y)
                ev.sound(Sfx.MELEE_HIT, e.x, e.y)
                if (e.attackKind == 3) { ev.shake(0.7f); ev.sound(Sfx.EXPLOSION, e.x, e.y, 0.5f) }
            }
            return
        }
        val tx = p.x; val ty = p.y; val tz = p.z + PLAYER_H * 0.6f
        val aim = atan2(ty - e.y, tx - e.x)
        val horiz = sqrt((tx - e.x) * (tx - e.x) + (ty - e.y) * (ty - e.y)).coerceAtLeast(0.1f)
        val pitch = atan2(tz - ez, horiz)
        val sm = Difficulty.PROJ_SPEED[difficulty]
        when (t.ranged) {
            Enemies.R_HITSCAN -> {
                ev.sound(Sfx.ENEMY_RIFLE, e.x, e.y)
                lights.add(DynLight(e.x, e.y, ez, 3f, 1.2f, 0.8f, 0.4f, 0.08f))
                val spread = 0.06f + min(0.08f, dist * 0.004f)
                val yaw = aim + rng.signed() * spread
                val cp = cos(pitch)
                val dx = cos(yaw) * cp; val dy = sin(yaw) * cp; val dz = sin(pitch)
                phys.trace(e.x, e.y, ez, dx, dy, dz, 40f, hit)
                val tp = phys.rayCylinder(e.x, e.y, ez, dx, dy, dz, p.x, p.y, p.z, PLAYER_R + 0.05f, PLAYER_H)
                if (tp >= 0f && tp < hit.t) hurtPlayer(rng.range(t.rangedMin.toFloat(), t.rangedMax.toFloat()).toInt(), e.x, e.y)
                else if (hit.kind == Hit.WALL) {
                    val hx = e.x + dx * hit.t; val hy = e.y + dy * hit.t; val hz = ez + dz * hit.t
                    glows.spawn(hx, hy, hz, hit.nx, hit.ny, 1f, 0.15f, 0.05f, 1f, 0.8f, 0.4f, 6f)
                }
            }
            Enemies.R_ACID -> {
                ev.sound(Sfx.ACID, e.x, e.y)
                spawnEnemyProjectile(Projectile.ACID, e, ez, aim, pitch + 0.03f, 7f * sm, rng.range(t.rangedMin.toFloat(), t.rangedMax.toFloat()).toInt(), 0, 0f)
            }
            Enemies.R_PLASMA -> {
                ev.sound(Sfx.PLASMA, e.x, e.y)
                spawnEnemyProjectile(Projectile.PLASMA, e, ez, aim, pitch, 11f * sm, rng.range(t.rangedMin.toFloat(), t.rangedMax.toFloat()).toInt(), 0, 0f)
            }
            Enemies.R_BOSS -> when (e.attackKind) {
                0 -> {
                    ev.sound(Sfx.ROCKET_FIRE, e.x, e.y)
                    for (k in -1..1) spawnEnemyProjectile(Projectile.BOSS_ROCKET, e, e.z + 1.6f, aim + k * 0.16f, atan2(tz - (e.z + 1.6f), horiz), 9f * sm, rng.range(15f, 30f).toInt(), 50, 2f)
                }
                1 -> {
                    ev.sound(Sfx.ACID, e.x, e.y)
                    for (k in -2..2) spawnEnemyProjectile(Projectile.ACID, e, e.z + 1.2f, aim + k * 0.2f, atan2(tz - (e.z + 1.2f), horiz) + 0.04f, 8f * sm, rng.range(8f, 16f).toInt(), 0, 0f)
                }
                2 -> summon(e)
            }
        }
    }

    private fun summon(e: Enemy) {
        val alive = enemies.count { it.alive && it.summoned }
        if (alive >= 6) return
        var spawned = 0
        for (attempt in 0 until 12) {
            if (spawned >= 2) break
            val a = rng.range(0f, 6.283f); val r = rng.range(2f, 3.5f)
            val x = e.x + cos(a) * r; val y = e.y + sin(a) * r
            val c = level.cellAt(x, y) ?: continue
            if (c.solid || c.lava) continue
            val ty = Enemies.types[Enemies.CRAWLER]
            if (phys.blocked(x, y, ty.radius, c.floor, ty.height, false)) continue
            val n = Enemy(ty, x, y, c.floor)
            n.summoned = true; n.counted = false
            n.state = Enemy.CHASE; n.cooldown = 1f
            enemies.add(n)
            for (i in 0 until 12) glows.spawn(x, y, c.floor + 0.2f, rng.signed() * 1.5f, rng.signed() * 1.5f, rng.range(0.5f, 2.5f), 0.5f, 0.2f, 0.8f, 0.3f, 1f, 0f, 0.5f)
            spawned++
        }
        if (spawned > 0) ev.sound(Sfx.SIGHT_CRAWLER, e.x, e.y)
    }

    private fun spawnEnemyProjectile(kind: Int, e: Enemy, z: Float, yaw: Float, pitch: Float, speed: Float, dmg: Int, splash: Int, radius: Float) {
        val cp = cos(pitch)
        val dx = cos(yaw) * cp; val dy = sin(yaw) * cp; val dz = sin(pitch)
        val off = e.type.radius + 0.15f
        projectiles.add(Projectile(kind, e.x + dx * off, e.y + dy * off, z, dx * speed, dy * speed, dz * speed, false, dmg, splash, radius))
    }

    private fun turnTo(cur: Float, target: Float, maxStep: Float): Float {
        val d = wrapAngle(target - cur)
        return wrapAngle(cur + clamp(d, -maxStep, maxStep))
    }

    private fun separateEnemies() {
        for (i in enemies.indices) {
            val a = enemies[i]
            if (!a.alive || a.type.id == Enemies.BARREL) continue
            for (j in enemies.indices) {
                if (i == j) continue
                val b = enemies[j]
                if (!b.alive) continue
                if (a.type.flying != b.type.flying) continue
                val dx = a.x - b.x; val dy = a.y - b.y
                val rr = a.type.radius + b.type.radius
                val d2 = dx * dx + dy * dy
                if (d2 >= rr * rr || d2 < 1e-6f) continue
                val d = sqrt(d2)
                val push = (rr - d) * (if (b.type.id == Enemies.BARREL) 1f else 0.5f)
                val nx = a.x + dx / d * push; val ny = a.y + dy / d * push
                if (!phys.blocked(nx, ny, a.type.radius, a.z, a.type.height, a.type.flying)) { a.x = nx; a.y = ny }
            }
            for (pr in props) {
                val dx = a.x - pr.x; val dy = a.y - pr.y
                val rr = a.type.radius + pr.radius
                val d2 = dx * dx + dy * dy
                if (d2 >= rr * rr || d2 < 1e-6f) continue
                val d = sqrt(d2)
                val nx = pr.x + dx / d * rr; val ny = pr.y + dy / d * rr
                if (!phys.blocked(nx, ny, a.type.radius, a.z, a.type.height, a.type.flying)) { a.x = nx; a.y = ny }
            }
        }
    }

    // ------------------------------------------------------------------ champs de navigation

    private fun cellIndex(x: Float, y: Float): Int {
        val cx = kotlin.math.floor(x).toInt(); val cy = kotlin.math.floor(y).toInt()
        if (cx < 0 || cy < 0 || cx >= level.w || cy >= level.h) return -1
        return cy * level.w + cx
    }

    private val heap = IntArray(level.w * level.h * 8 + 16)
    private var heapSize = 0

    private fun heapPush(d: Int, idx: Int, dist: IntArray) {
        var i = heapSize++
        heap[i] = idx
        while (i > 0) {
            val parent = (i - 1) / 2
            if (dist[heap[parent]] <= d) break
            heap[i] = heap[parent]; i = parent
        }
        heap[i] = idx
    }

    private fun heapPop(dist: IntArray): Int {
        val top = heap[0]
        val last = heap[--heapSize]
        var i = 0
        while (true) {
            val l = i * 2 + 1
            if (l >= heapSize) break
            val r = l + 1
            val c = if (r < heapSize && dist[heap[r]] < dist[heap[l]]) r else l
            if (dist[heap[c]] >= dist[last]) break
            heap[i] = heap[c]; i = c
        }
        heap[i] = last
        return top
    }

    private fun passable(fromIdx: Int, toIdx: Int, air: Boolean): Boolean {
        val w = level.w
        val to = level.cell(toIdx % w, toIdx / w) ?: return false
        val from = level.cell(fromIdx % w, fromIdx / w) ?: return false
        if (to.solid) return false
        val d = to.door
        if (d != null && (d.key >= 0 || d.secret) && d.open < 0.9f) return false
        if (air) return level.effCeil(to).coerceAtLeast(d?.top ?: 0f) - to.floor > 0.5f
        // Le monstre va de "to" vers "from" (on part du joueur)
        if (from.floor - to.floor > Physics.STEP) return false
        if (to.crate) return false
        return true
    }

    private fun computeFlow() {
        computeField(groundDist, false)
        computeField(airDist, true)
    }

    private fun computeField(dist: IntArray, air: Boolean) {
        java.util.Arrays.fill(dist, INF)
        val start = cellIndex(player.x, player.y)
        if (start < 0) return
        val w = level.w
        heapSize = 0
        dist[start] = 0
        heapPush(0, start, dist)
        val visited = BooleanArray(dist.size)
        while (heapSize > 0) {
            val i = heapPop(dist)
            if (visited[i]) continue
            visited[i] = true
            val cx = i % w; val cy = i / w
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx; val ny = cy + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= level.h) continue
                val ni = ny * w + nx
                if (visited[ni]) continue
                if (!passable(i, ni, air)) continue
                if (dx != 0 && dy != 0) {
                    if (!passable(i, cy * w + nx, air) || !passable(i, ny * w + cx, air)) continue
                }
                var cost = if (dx != 0 && dy != 0) 14 else 10
                if (!air && level.cell(nx, ny)!!.lava) cost += 60
                val nd = dist[i] + cost
                if (nd < dist[ni]) {
                    dist[ni] = nd
                    if (heapSize < heap.size) heapPush(nd, ni, dist)
                }
            }
        }
    }

    private var flowX = 0f
    private var flowY = 0f

    private fun flowDir(e: Enemy): Boolean {
        val field = if (e.type.flying) airDist else groundDist
        val i = cellIndex(e.x, e.y)
        if (i < 0) return false
        val w = level.w
        val cx = i % w; val cy = i / w
        var best = field[i]; var bx = -1; var by = -1
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = cx + dx; val ny = cy + dy
            if (nx < 0 || ny < 0 || nx >= w || ny >= level.h) continue
            val v = field[ny * w + nx]
            if (v < best) { best = v; bx = nx; by = ny }
        }
        if (bx < 0) return false
        flowX = bx + 0.5f - e.x; flowY = by + 0.5f - e.y
        return true
    }

    fun flowDistanceAt(x: Float, y: Float): Int { val i = cellIndex(x, y); return if (i < 0) INF else groundDist[i] }

    // ------------------------------------------------------------------ projectiles & effets

    private fun updateProjectiles(dt: Float) {
        val p = player
        var i = 0
        while (i < projectiles.size) {
            val pr = projectiles[i]
            pr.life -= dt; pr.age += dt
            val steps = max(1, (sqrt(pr.vx * pr.vx + pr.vy * pr.vy + pr.vz * pr.vz) * dt / 0.2f).toInt() + 1)
            val sdt = dt / steps
            for (s in 0 until steps) {
                if (pr.dead) break
                pr.x += pr.vx * sdt; pr.y += pr.vy * sdt; pr.z += pr.vz * sdt
                val c = level.cellAt(pr.x, pr.y)
                if (c == null || c.solid || pr.z < c.floor || pr.z > level.effCeil(c)) {
                    if (c != null && c.sky && pr.z > c.floor) { pr.dead = true; break }
                    projectileImpact(pr); break
                }
                if (pr.fromPlayer) {
                    for (e in enemies) {
                        if (!e.alive) continue
                        val dx = pr.x - e.x; val dy = pr.y - e.y
                        val rr = e.type.radius + 0.12f
                        if (dx * dx + dy * dy < rr * rr && pr.z > e.z - 0.1f && pr.z < e.z + e.type.height + 0.1f) {
                            hurtEnemy(e, pr.damage, pr.x - pr.vx * 0.05f, pr.y - pr.vy * 0.05f, true, 1f)
                            projectileImpact(pr); break
                        }
                    }
                } else if (!p.dead) {
                    val dx = pr.x - p.x; val dy = pr.y - p.y
                    val rr = PLAYER_R + 0.12f
                    if (dx * dx + dy * dy < rr * rr && pr.z > p.z - 0.1f && pr.z < p.z + PLAYER_H + 0.1f) {
                        hurtPlayer(pr.damage, pr.x - pr.vx, pr.y - pr.vy)
                        projectileImpact(pr)
                    } else {
                        for (e in enemies) {
                            if (!e.alive || e.type.id != Enemies.BARREL) continue
                            val ex = pr.x - e.x; val ey = pr.y - e.y
                            if (ex * ex + ey * ey < 0.12f && pr.z < e.z + e.type.height) { hurtEnemy(e, pr.damage, pr.x, pr.y, false, 0f); projectileImpact(pr); break }
                        }
                    }
                }
            }
            // Traînées
            if (!pr.dead) when (pr.kind) {
                Projectile.ROCKET, Projectile.BOSS_ROCKET -> glows.spawn(pr.x, pr.y, pr.z, rng.signed() * 0.2f, rng.signed() * 0.2f, rng.range(0.1f, 0.5f), 0.45f, 0.09f, 0.7f, 0.6f, 0.5f, 0f, 0.4f)
                Projectile.ANNIHIL -> glows.spawn(pr.x + rng.signed() * 0.15f, pr.y + rng.signed() * 0.15f, pr.z + rng.signed() * 0.15f, 0f, 0f, 0f, 0.3f, 0.15f, 0.3f, 1f, 0.4f, 0f, -0.3f)
                Projectile.ACID -> if (rng.chance(0.5f)) glows.spawn(pr.x, pr.y, pr.z, 0f, 0f, -0.3f, 0.25f, 0.06f, 0.4f, 1f, 0.2f)
            }
            if (pr.life <= 0f && !pr.dead) projectileImpact(pr)
            if (pr.dead) projectiles.removeAt(i) else i++
        }
    }

    private fun projectileImpact(pr: Projectile) {
        if (pr.dead) return
        pr.dead = true
        val bx = pr.x - pr.vx * 0.012f; val by = pr.y - pr.vy * 0.012f; val bz = pr.z - pr.vz * 0.012f
        when (pr.kind) {
            Projectile.ROCKET -> booms.add(PendingBoom(bx, by, bz, pr.radius, pr.splash, true, true, 0f, false))
            Projectile.BOSS_ROCKET -> booms.add(PendingBoom(bx, by, bz, pr.radius, pr.splash, false, true, 0f, false))
            Projectile.ANNIHIL -> booms.add(PendingBoom(bx, by, bz, pr.radius, pr.splash, true, false, 0f, true))
            Projectile.ACID -> {
                ev.sound(Sfx.SPLAT, bx, by, 0.6f)
                for (k in 0 until 8) glows.spawn(bx, by, bz, rng.signed() * 2f, rng.signed() * 2f, rng.range(0f, 2f), 0.35f, 0.08f, 0.5f, 1f, 0.2f, 6f)
            }
            Projectile.PLASMA -> {
                for (k in 0 until 8) glows.spawn(bx, by, bz, rng.signed() * 2f, rng.signed() * 2f, rng.signed() * 2f, 0.3f, 0.08f, 1f, 0.3f, 0.3f)
                lights.add(DynLight(bx, by, bz, 2.5f, 1f, 0.3f, 0.3f, 0.15f))
            }
        }
    }

    private fun updateParticles(dt: Float) {
        var i = 0
        val d = debris
        while (i < d.count) {
            d.life[i] -= dt
            if (d.life[i] <= 0f) { d.remove(i); continue }
            d.vz[i] -= d.gravity[i] * dt
            var nx = d.x[i] + d.vx[i] * dt; var ny = d.y[i] + d.vy[i] * dt
            val c = level.cellAt(nx, ny)
            if (c == null || c.solid) { d.vx[i] *= -0.3f; d.vy[i] *= -0.3f; nx = d.x[i]; ny = d.y[i] }
            d.x[i] = nx; d.y[i] = ny
            d.z[i] += d.vz[i] * dt
            val floor = (c?.floor ?: 0f) + d.size[i]
            if (d.z[i] < floor) {
                d.z[i] = floor
                d.vz[i] *= -0.3f; d.vx[i] *= 0.6f; d.vy[i] *= 0.6f
            } else d.rot[i] += dt * 8f
            i++
        }
        i = 0
        val g = glows
        while (i < g.count) {
            g.life[i] -= dt
            if (g.life[i] <= 0f) { g.remove(i); continue }
            g.vz[i] -= g.gravity[i] * dt
            g.x[i] += g.vx[i] * dt; g.y[i] += g.vy[i] * dt; g.z[i] += g.vz[i] * dt
            g.vx[i] *= 1f - min(1f, dt * 2f); g.vy[i] *= 1f - min(1f, dt * 2f)
            g.size[i] = max(0.01f, g.size[i] + g.grow[i] * dt)
            i++
        }
    }

    private fun updateLights(dt: Float) {
        var i = 0
        while (i < lights.size) {
            val l = lights[i]
            l.life -= dt
            if (l.life <= 0f) lights.removeAt(i) else i++
        }
    }

    private fun markSeen() {
        val p = player
        for (k in 0 until 48) {
            val a = k * (6.2831855f / 48f)
            val dx = cos(a) * 0.4f; val dy = sin(a) * 0.4f
            var x = p.x; var y = p.y
            for (s in 0 until 40) {
                val c = level.cellAt(x, y) ?: break
                c.seen = true
                if (c.solid) break
                val dr = c.door
                if (dr != null && dr.open < 0.5f) break
                x += dx; y += dy
            }
        }
    }

    val aliveBoss get() = boss?.alive == true

    private val KEY_NAMES = arrayOf("rouge", "bleue", "jaune")
    private val BLOOD_RED = floatArrayOf(0.55f, 0.05f, 0.08f)
    private val BLOOD_GREEN = floatArrayOf(0.35f, 0.7f, 0.1f)
    private val BLOOD_SPARK = floatArrayOf(0.3f, 0.3f, 0.32f)
}
