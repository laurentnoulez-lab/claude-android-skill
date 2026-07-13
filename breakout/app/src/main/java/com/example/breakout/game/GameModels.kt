package com.example.breakout.game

/** Phase courante de la partie. */
enum class GameStatus {
    /** La balle est collée à la raquette, en attente d'un tap pour être lancée. */
    READY,
    RUNNING,
    PAUSED,
    LEVEL_CLEARED,
    GAME_OVER,
    /** Tous les niveaux sont terminés. */
    WON,
}

enum class PowerUpType(val isMalus: Boolean) {
    /** Bonus : élargit temporairement la raquette. */
    EXPAND(isMalus = false),
    /** Bonus : ajoute une vie. */
    EXTRA_LIFE(isMalus = false),
    /** Bonus : divise chaque balle en trois. */
    MULTI_BALL(isMalus = false),
    /** Bonus : ralentit temporairement les balles. */
    SLOW_BALL(isMalus = false),
    /** Bonus : les balles tirent des lasers tout autour d'elles. */
    LASER_BALL(isMalus = false),
    /** Bonus : les balles grossissent temporairement. */
    BIG_BALL(isMalus = false),
    /** Malus : accélère temporairement les balles. */
    FAST_BALL(isMalus = true),
    /** Malus : rétrécit temporairement la raquette. */
    SHRINK_PADDLE(isMalus = true),
}

class Ball(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var radius: Float,
)

class Brick(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    var hp: Int,
    val maxHp: Int,
) {
    val alive: Boolean get() = hp > 0
    val points: Int get() = maxHp * 10
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

class PowerUp(
    var x: Float,
    var y: Float,
    val type: PowerUpType,
    val size: Float,
)

/** Projectile laser émis par une balle sous l'effet du bonus LASER_BALL. */
class Laser(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
)

/**
 * Callbacks émis par le moteur, typiquement branchés sur les effets
 * sonores. Toutes les méthodes ont une implémentation vide pour que
 * les tests puissent ignorer ce qui ne les intéresse pas.
 */
interface GameEvents {
    fun onWallHit() {}
    fun onPaddleHit() {}
    fun onBrickHit(destroyed: Boolean) {}
    fun onPowerUpCaught(type: PowerUpType) {}
    fun onBallLost() {}
    fun onLevelCleared() {}
    fun onGameOver() {}
}
