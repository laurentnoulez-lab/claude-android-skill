package com.example.breakout

import android.content.Context

/** Persistance du meilleur score via SharedPreferences. */
class HighScoreStore(context: Context) {

    private val prefs = context.getSharedPreferences("breakout", Context.MODE_PRIVATE)

    fun highScore(): Int = prefs.getInt(KEY_HIGH_SCORE, 0)

    /** Enregistre [score] s'il bat le record. Retourne true si record battu. */
    fun submit(score: Int): Boolean {
        if (score <= highScore()) return false
        prefs.edit().putInt(KEY_HIGH_SCORE, score).apply()
        return true
    }

    private companion object {
        const val KEY_HIGH_SCORE = "high_score"
    }
}
