package com.example.breakout.sound

import android.media.AudioManager
import android.media.ToneGenerator
import com.example.breakout.game.GameEvents
import com.example.breakout.game.PowerUpType

/**
 * Effets sonores minimalistes basés sur [ToneGenerator] : aucun asset audio
 * nécessaire. Chaque appel est protégé car certains appareils refusent de
 * créer un ToneGenerator (ressources audio épuisées).
 */
class SoundEffects : GameEvents {

    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 60)
    } catch (e: RuntimeException) {
        null
    }

    private fun play(tone: Int, durationMs: Int) {
        try {
            toneGenerator?.startTone(tone, durationMs)
        } catch (_: RuntimeException) {
            // Son indisponible : le jeu continue en silence.
        }
    }

    override fun onWallHit() = play(ToneGenerator.TONE_CDMA_PIP, 30)

    override fun onPaddleHit() = play(ToneGenerator.TONE_PROP_BEEP, 40)

    override fun onBrickHit(destroyed: Boolean) =
        play(if (destroyed) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 40)

    override fun onPowerUpCaught(type: PowerUpType) = play(ToneGenerator.TONE_PROP_ACK, 90)

    override fun onBallLost() = play(ToneGenerator.TONE_SUP_ERROR, 200)

    override fun onGameOver() = play(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 400)

    fun release() {
        toneGenerator?.release()
    }
}
