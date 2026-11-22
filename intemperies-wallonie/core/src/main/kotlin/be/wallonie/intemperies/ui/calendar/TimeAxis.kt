package be.wallonie.intemperies.ui.calendar

/**
 * Graduations de l'axe horaire, calculées à partir de la plage de travail PARAMÉTRÉE
 * (début / fin en secondes depuis minuit) : l'axe suit automatiquement les réglages de l'utilisateur.
 */
object TimeAxis {
    private val INTERVALS_H = listOf(1, 2, 3, 4, 6)

    /** Heures pleines comprises dans la plage, espacées d'au moins [minSpacingPx] ; à défaut, début et fin. */
    fun ticks(startSec: Int, endSec: Int, widthPx: Float, minSpacingPx: Float): List<Int> {
        if (endSec <= startSec || widthPx <= 0f) return emptyList()
        val pxPerHour = widthPx * 3600f / (endSec - startSec)
        val interval = INTERVALS_H.firstOrNull { it * pxPerHour >= minSpacingPx } ?: INTERVALS_H.last()
        val first = ((startSec + 3599) / 3600)
        val ticks = (first..endSec / 3600)
            .filter { it % interval == 0 }
            .map { it * 3600 }
            .filter { it in startSec..endSec }
        return ticks.ifEmpty { listOf(startSec, endSec) }
    }

    fun label(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (m == 0) "${h}h" else "${h}h%02d".format(m)
    }
}
