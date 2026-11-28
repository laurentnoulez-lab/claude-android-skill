package io.quarantaine.game

class Player {
    var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var yaw = 0f; var pitch = 0f
    /** Hauteur de caméra lissée (montée des marches). */
    var eyeZ = 0f
    var health = 100
    var armor = 0
    var armorHeavy = false
    val owned = BooleanArray(Weapons.COUNT).also { it[Weapons.FIST] = true; it[Weapons.PISTOL] = true }
    val ammo = intArrayOf(50, 0, 0, 0)
    var weapon = Weapons.PISTOL
    var pendingWeapon = -1
    /** 0 = arme en place, 1 = arme baissée (changement d'arme). */
    var switchAnim = 0f
    var cooldown = 0f
    var fireAnim = 0f
    var flash = 0f
    var keys = BooleanArray(3)
    var dead = false
    var deadTime = 0f
    var bobPhase = 0f
    var bobAmount = 0f
    var hurtFlash = 0f
    var pickupFlash = 0f
    var hurtDirTime = 0f
    var hurtDirAngle = 0f
    var lavaTimer = 0f
    var chargeTime = 0f
    var spin = 0f

    fun copyInventoryFrom(o: Player) {
        health = o.health; armor = o.armor; armorHeavy = o.armorHeavy
        for (i in owned.indices) owned[i] = o.owned[i]
        for (i in ammo.indices) ammo[i] = o.ammo[i]
        weapon = o.weapon
    }

    fun serialize(): String =
        listOf(health, armor, if (armorHeavy) 1 else 0, owned.joinToString("") { if (it) "1" else "0" }, ammo.joinToString(","), weapon).joinToString(";")

    fun deserialize(s: String): Boolean = try {
        val p = s.split(";")
        health = p[0].toInt().coerceIn(1, 200)
        armor = p[1].toInt().coerceIn(0, 200)
        armorHeavy = p[2] == "1"
        p[3].forEachIndexed { i, c -> if (i < owned.size) owned[i] = c == '1' }
        owned[Weapons.FIST] = true; owned[Weapons.PISTOL] = true
        p[4].split(",").forEachIndexed { i, v -> if (i < ammo.size) ammo[i] = v.toInt().coerceIn(0, Ammo.MAX[i]) }
        weapon = p[5].toInt().coerceIn(0, Weapons.COUNT - 1).let { if (owned[it]) it else Weapons.PISTOL }
        true
    } catch (e: Exception) { false }
}

class Enemy(val type: EnemyType, var x: Float, var y: Float, var z: Float) {
    var hp = type.hp
    var yaw = 0f
    var state = IDLE
    var stateTime = 0f
    var cooldown = 1f
    var walkPhase = 0f
    var hoverZ = z
    var stuckTime = 0f
    var wanderX = 0f; var wanderY = 0f; var wanderTime = 0f
    var strafeDir = 1f; var strafeTime = 0f
    var sightTimer = 0f
    var hitFlash = 0f
    var deathTime = 0f
    var summoned = false
    var attackKind = 0
    var bossTimer = 0f
    var bossSummon = 8f
    var chargeTime = 0f
    var counted = true
    val alive get() = state != DEAD

    companion object {
        const val IDLE = 0
        const val CHASE = 1
        const val WINDUP = 2
        const val PAIN = 3
        const val DEAD = 4
        const val CHARGE = 5
    }
}

class Projectile(
    var kind: Int, var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float, var vz: Float,
    val fromPlayer: Boolean, val damage: Int, val splash: Int, val radius: Float,
) {
    var life = 6f
    var dead = false
    var age = 0f

    companion object {
        const val ROCKET = 0
        const val ANNIHIL = 1
        const val ACID = 2
        const val PLASMA = 3
        const val BOSS_ROCKET = 4
    }
}

class Pickup(val ch: Char, val x: Float, val y: Float, val z: Float) {
    var taken = false
    var phase = (x * 13.7f + y * 7.3f) % 6.28f
}

/** Obstacle décoratif fixe (colonne lumineuse). */
class Prop(val x: Float, val y: Float, val z: Float, val radius: Float)

class DynLight(var x: Float, var y: Float, var z: Float, var radius: Float, var r: Float, var g: Float, var b: Float, var life: Float) {
    var maxLife = life
}

/** Systèmes de particules en tableaux préalloués (aucune allocation par image). */
class Particles(val capacity: Int) {
    val x = FloatArray(capacity); val y = FloatArray(capacity); val z = FloatArray(capacity)
    val vx = FloatArray(capacity); val vy = FloatArray(capacity); val vz = FloatArray(capacity)
    val life = FloatArray(capacity); val maxLife = FloatArray(capacity)
    val size = FloatArray(capacity); val grow = FloatArray(capacity)
    val r = FloatArray(capacity); val g = FloatArray(capacity); val b = FloatArray(capacity)
    val rot = FloatArray(capacity); val gravity = FloatArray(capacity)
    var count = 0

    fun spawn(px: Float, py: Float, pz: Float, pvx: Float, pvy: Float, pvz: Float, l: Float, s: Float, cr: Float, cg: Float, cb: Float, grav: Float = 0f, gr: Float = 0f) {
        val i = if (count < capacity) count++ else oldest()
        x[i] = px; y[i] = py; z[i] = pz; vx[i] = pvx; vy[i] = pvy; vz[i] = pvz
        life[i] = l; maxLife[i] = l; size[i] = s; grow[i] = gr
        r[i] = cr; g[i] = cg; b[i] = cb; rot[i] = (px * 31f + pz * 17f) % 6.28f; gravity[i] = grav
    }

    private fun oldest(): Int {
        var best = 0; var bl = Float.MAX_VALUE
        for (i in 0 until count) if (life[i] < bl) { bl = life[i]; best = i }
        return best
    }

    fun remove(i: Int) {
        val last = --count
        if (i != last) {
            x[i] = x[last]; y[i] = y[last]; z[i] = z[last]; vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last]
            life[i] = life[last]; maxLife[i] = maxLife[last]; size[i] = size[last]; grow[i] = grow[last]
            r[i] = r[last]; g[i] = g[last]; b[i] = b[last]; rot[i] = rot[last]; gravity[i] = gravity[last]
        }
    }

    fun clear() { count = 0 }
}
