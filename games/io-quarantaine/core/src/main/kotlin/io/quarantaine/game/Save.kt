package io.quarantaine.game

import io.quarantaine.platform.Host

/** Réglages du joueur (persistés). */
class Settings {
    var sensitivity = 1f
    var invertY = false
    var aimAssist = true
    var vibration = true
    var musicVolume = 0.55f
    var sfxVolume = 0.9f

    fun load(h: Host) {
        sensitivity = h.load("set.sens")?.toFloatOrNull()?.coerceIn(0.3f, 3f) ?: 1f
        invertY = h.load("set.invert") == "1"
        aimAssist = h.load("set.aim") != "0"
        vibration = h.load("set.vibe") != "0"
        musicVolume = h.load("set.music")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.55f
        sfxVolume = h.load("set.sfx")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.9f
    }

    fun save(h: Host) {
        h.save("set.sens", sensitivity.toString())
        h.save("set.invert", if (invertY) "1" else "0")
        h.save("set.aim", if (aimAssist) "1" else "0")
        h.save("set.vibe", if (vibration) "1" else "0")
        h.save("set.music", musicVolume.toString())
        h.save("set.sfx", sfxVolume.toString())
    }
}

/** Progression : secteur à reprendre, inventaire en début de secteur, records. */
class Progress {
    var level = -1
    var difficulty = 1
    var inventory = ""
    var completed = false

    fun load(h: Host) {
        level = h.load("prog.level")?.toIntOrNull() ?: -1
        difficulty = h.load("prog.diff")?.toIntOrNull()?.coerceIn(0, 2) ?: 1
        inventory = h.load("prog.inv") ?: ""
        completed = h.load("prog.done") == "1"
    }

    fun save(h: Host) {
        h.save("prog.level", level.toString())
        h.save("prog.diff", difficulty.toString())
        h.save("prog.inv", inventory)
        h.save("prog.done", if (completed) "1" else "0")
    }

    fun bestTime(h: Host, level: Int, diff: Int): Float? = h.load("best.$level.$diff")?.toFloatOrNull()

    /** Enregistre un temps ; renvoie true s'il s'agit d'un nouveau record. */
    fun submitTime(h: Host, level: Int, diff: Int, time: Float): Boolean {
        val prev = bestTime(h, level, diff)
        if (prev == null || time < prev) { h.save("best.$level.$diff", time.toString()); return true }
        return false
    }
}
