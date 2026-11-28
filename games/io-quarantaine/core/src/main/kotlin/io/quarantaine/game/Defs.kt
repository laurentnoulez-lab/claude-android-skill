package io.quarantaine.game

object Ammo {
    const val NONE = -1
    const val BULLETS = 0
    const val SHELLS = 1
    const val ROCKETS = 2
    const val CELLS = 3
    val MAX = intArrayOf(200, 50, 50, 300)
    val NAMES = arrayOf("BALLES", "CARTOUCHES", "ROQUETTES", "CELLULES")
}

class WeaponDef(
    val name: String,
    val short: String,
    val ammo: Int,
    val perShot: Int,
    val cooldown: Float,
    val dmgMin: Int,
    val dmgMax: Int,
    val pellets: Int = 1,
    val spreadH: Float = 0f,
    val spreadV: Float = 0f,
    val projectile: Int = -1,
    val recoil: Float = 0.1f,
)

object Weapons {
    const val FIST = 0
    const val PISTOL = 1
    const val SHOTGUN = 2
    const val CHAINGUN = 3
    const val ROCKET = 4
    const val ANNIHILATOR = 5
    const val COUNT = 6

    val defs = arrayOf(
        WeaponDef("GANTELET À IMPULSION", "POING", Ammo.NONE, 0, 0.45f, 15, 35, recoil = 0.25f),
        WeaponDef("PISTOLET P-9", "P-9", Ammo.BULLETS, 1, 0.32f, 10, 18, spreadH = 0.012f, recoil = 0.12f),
        WeaponDef("FUSIL À POMPE", "POMPE", Ammo.SHELLS, 1, 0.95f, 6, 12, pellets = 8, spreadH = 0.09f, spreadV = 0.035f, recoil = 0.3f),
        WeaponDef("MITRAILLEUSE ROTATIVE", "MITRAIL.", Ammo.BULLETS, 1, 0.095f, 9, 15, spreadH = 0.035f, spreadV = 0.01f, recoil = 0.06f),
        WeaponDef("LANCE-ROQUETTES", "ROQUETTES", Ammo.ROCKETS, 1, 0.8f, 30, 60, projectile = Projectile.ROCKET, recoil = 0.3f),
        WeaponDef("ANNIHILATEUR", "ANNIHIL.", Ammo.CELLS, 40, 1.4f, 250, 250, projectile = Projectile.ANNIHIL, recoil = 0.45f),
    )
}

class EnemyType(
    val id: Int,
    val name: String,
    val radius: Float,
    val height: Float,
    val hp: Int,
    val speed: Float,
    val meleeMin: Int,
    val meleeMax: Int,
    val meleeRange: Float,
    val ranged: Int,
    val rangedMin: Int,
    val rangedMax: Int,
    val rangedRange: Float,
    val cooldownMin: Float,
    val cooldownMax: Float,
    val windup: Float,
    val painChance: Float,
    val flying: Boolean = false,
    val countsAsKill: Boolean = true,
)

object Enemies {
    const val MINER = 0
    const val CRAWLER = 1
    const val SPITTER = 2
    const val BRUTE = 3
    const val DRONE = 4
    const val BOSS = 5
    const val BARREL = 6

    const val R_NONE = 0
    const val R_HITSCAN = 1
    const val R_ACID = 2
    const val R_PLASMA = 3
    const val R_BOSS = 4

    val types = arrayOf(
        EnemyType(MINER, "Mineur infecté", 0.26f, 0.62f, 25, 1.7f, 4, 10, 0.8f, R_HITSCAN, 4, 11, 14f, 1.4f, 2.6f, 0.45f, 0.7f),
        EnemyType(CRAWLER, "Rampant", 0.3f, 0.32f, 18, 3.3f, 5, 12, 0.75f, R_NONE, 0, 0, 0f, 0.8f, 1.1f, 0.25f, 0.5f),
        EnemyType(SPITTER, "Cracheur", 0.32f, 0.66f, 60, 1.4f, 6, 12, 0.85f, R_ACID, 10, 18, 16f, 1.9f, 3.0f, 0.55f, 0.4f),
        EnemyType(BRUTE, "Brute", 0.45f, 1.0f, 180, 1.9f, 18, 32, 1.05f, R_NONE, 0, 0, 0f, 1.0f, 1.4f, 0.45f, 0.15f),
        EnemyType(DRONE, "Drone sentinelle", 0.3f, 0.4f, 45, 2.4f, 4, 8, 0.7f, R_PLASMA, 6, 12, 16f, 1.4f, 2.2f, 0.35f, 0.6f, flying = true),
        EnemyType(BOSS, "L'Hôte", 0.95f, 2.3f, 2400, 1.35f, 30, 50, 1.9f, R_BOSS, 0, 0, 40f, 1.6f, 2.4f, 0.7f, 0.03f),
        EnemyType(BARREL, "Baril", 0.27f, 0.55f, 12, 0f, 0, 0, 0f, R_NONE, 0, 0, 0f, 99f, 99f, 0f, 0f, countsAsKill = false),
    )

    fun forChar(c: Char): Int = when (c) {
        'z' -> MINER; 'c' -> CRAWLER; 's' -> SPITTER; 'b' -> BRUTE; 'd' -> DRONE; 'M' -> BOSS; 't' -> BARREL
        else -> -1
    }
}

object Difficulty {
    val NAMES = arrayOf("RECRUE", "SOLDAT", "CAUCHEMAR")
    val DESCRIPTIONS = arrayOf("Dégâts reçus réduits, moins d'ennemis", "L'expérience prévue", "Ennemis agressifs, dégâts accrus")
    val DAMAGE_TAKEN = floatArrayOf(0.5f, 1f, 1.4f)
    val COOLDOWN = floatArrayOf(1.35f, 1f, 0.72f)
    val PROJ_SPEED = floatArrayOf(0.85f, 1f, 1.18f)
    val AMMO_PICKUP = floatArrayOf(1.5f, 1f, 1f)
}

object Pickups {
    fun isPickup(c: Char) = c in "hHaAmnopSGKUruy"
}
