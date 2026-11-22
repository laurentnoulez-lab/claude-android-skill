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

/** Débits statistiques du SPW pour ce jour du calendrier, toutes années de mesure confondues. */
data class DayStats(val p15: Double, val median: Double, val p85: Double, val p90: Double)

/** Classement du débit actuel par rapport aux statistiques du jour (P15 / P85 / P90). */
enum class FlowClass(val label: String) {
    FAIBLE("Courant faible"),
    NORMAL("Courant normal"),
    FORT("Courant fort"),
    TRES_FORT("Courant très fort"),
    ;

    companion object {
        fun of(flow: Double, stats: DayStats): FlowClass = when {
            flow < stats.p15 -> FAIBLE
            flow <= stats.p85 -> NORMAL
            flow <= stats.p90 -> FORT
            else -> TRES_FORT
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
    val dayStats: DayStats?,
    val fetchedAt: Instant,
) {
    val flowClass: FlowClass?
        get() = amayFlow?.let { f -> dayStats?.let { FlowClass.of(f.point.value, it) } }
}
