package com.example.breakout.game

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * État sérialisable complet d'une partie, pour la sauvegarde/reprise.
 * Les coordonnées sont exprimées dans le repère monde du moteur qui a
 * produit l'instantané ; [GameEngine.restore] les remet à l'échelle si
 * les dimensions du monde ont changé entre-temps.
 */
@Serializable
data class GameSnapshot(
    val version: Int = VERSION,
    val worldWidth: Float,
    val worldHeight: Float,
    val score: Int,
    val lives: Int,
    val level: Int,
    val status: GameStatus,
    val paddleX: Float,
    val paddleScale: Float,
    val paddleEffectTimer: Float,
    val slowTimer: Float,
    val slowActive: Boolean,
    val fastTimer: Float,
    val fastActive: Boolean,
    val laserTimer: Float,
    val bigBallTimer: Float,
    val bigBallActive: Boolean,
    val balls: List<BallState>,
    val bricks: List<BrickState>,
    val powerUps: List<PowerUpState>,
    val lasers: List<LaserState>,
) {

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }

        /** Retourne null si le JSON est corrompu ou d'une version inconnue. */
        fun fromJson(text: String): GameSnapshot? = try {
            json.decodeFromString(serializer(), text).takeIf { it.version == VERSION }
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class BallState(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val radius: Float,
)

@Serializable
data class BrickState(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val hp: Int,
    val maxHp: Int,
)

@Serializable
data class PowerUpState(
    val x: Float,
    val y: Float,
    val type: PowerUpType,
)

@Serializable
data class LaserState(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
)
