package com.example.breakout

import android.content.Context
import com.example.breakout.game.GameSnapshot

/**
 * Persistance de la partie en cours (SharedPreferences + JSON), pour
 * reprendre le jeu là où on l'a laissé après fermeture de l'application.
 */
class GameSaveStore(context: Context) {

    private val prefs = context.getSharedPreferences("breakout_save", Context.MODE_PRIVATE)

    /** Retourne null s'il n'y a pas de sauvegarde ou si elle est corrompue. */
    fun load(): GameSnapshot? =
        prefs.getString(KEY_SNAPSHOT, null)?.let { GameSnapshot.fromJson(it) }

    fun save(snapshot: GameSnapshot) {
        prefs.edit().putString(KEY_SNAPSHOT, snapshot.toJson()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_SNAPSHOT).apply()
    }

    private companion object {
        const val KEY_SNAPSHOT = "snapshot"
    }
}
