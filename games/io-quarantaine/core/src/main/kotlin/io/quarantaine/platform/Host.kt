package io.quarantaine.platform

/** Services fournis par la plateforme (Android ou banc de test). */
interface Host {
    /** Lecture d'une valeur persistée (null si absente). */
    fun load(key: String): String?
    /** Écriture d'une valeur persistée. */
    fun save(key: String, value: String)
    /** Retour haptique : durée en ms, intensité 0..1. */
    fun vibrate(ms: Int, strength: Float)
    /** Quitter l'application. */
    fun quit()
    fun log(msg: String)
}

/** Événement tactile en coordonnées de la surface de rendu (pixels). */
class TouchEvent(val action: Int, val pointerId: Int, val x: Float, val y: Float) {
    companion object {
        const val DOWN = 0
        const val MOVE = 1
        const val UP = 2
        const val CANCEL = 3
    }
}
