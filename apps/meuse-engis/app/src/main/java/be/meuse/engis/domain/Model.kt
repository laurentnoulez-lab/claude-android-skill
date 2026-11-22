package be.meuse.engis.domain

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

data class Point(val time: OffsetDateTime, val value: Double)

enum class Trend { HAUSSE, STABLE, BAISSE, INCONNUE }

/** Débit moyen d'une journée, calculé sur les moyennes horaires (00h → 23h, heure belge). */
data class DayBar(
    val date: LocalDate,
    val mean: Double?,
    val hours: Int,
    val isToday: Boolean,
)

data class Reading(val point: Point, val trend: Trend)

/** Seuils de courant réglables, en % du débit moyen habituel du mois (défaut : 70 % et 130 %). */
data class FlowThresholds(val lowPct: Int = 70, val highPct: Int = 130) {
    companion object {
        val DEFAULT = FlowThresholds()
        const val MIN_PCT = 20
        const val MAX_PCT = 300
    }
}

/** Classement du débit actuel par rapport au débit moyen habituel du mois. */
enum class FlowClass(val label: String, val shortLabel: String) {
    FAIBLE("Courant faible", "Faible"),
    NORMAL("Courant normal", "Normal"),
    ELEVE("Courant élevé", "Élevé"),
    ;

    companion object {
        /** Faible si < seuil bas, élevé si > seuil haut (bornes incluses dans « normal »). */
        fun of(flow: Double, monthMean: Double, t: FlowThresholds): FlowClass {
            val pct = flow / monthMean * 100.0
            return when {
                pct < t.lowPct -> FAIBLE
                pct > t.highPct -> ELEVE
                else -> NORMAL
            }
        }
    }
}

data class MeuseSnapshot(
    val bars: List<DayBar>,
    /** Moyenne interannuelle du mois (série SPW "InterAn.Mois.Moyen"). */
    val monthNormal: Double?,
    /** Moyenne des débits horaires du mois en cours, jusqu'à la dernière mesure. */
    val monthToDate: Double?,
    val amayFlow: Reading?,
    val ivozLevel: Reading?,
    val ivozFlow: Reading?,
    val fetchedAt: Instant,
) {
    fun flowClass(t: FlowThresholds): FlowClass? =
        amayFlow?.let { f -> monthNormal?.let { FlowClass.of(f.point.value, it, t) } }
}
