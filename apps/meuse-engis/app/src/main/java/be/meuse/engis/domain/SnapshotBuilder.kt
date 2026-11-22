package be.meuse.engis.domain

import be.meuse.engis.data.Series
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

object SnapshotBuilder {

    /** Nombre de jours complets affichés avant le jour en cours. */
    const val PAST_DAYS = 7L

    /** Début de la fenêtre à télécharger : couvre les 7 derniers jours ET le début du mois. */
    fun windowStart(today: LocalDate): LocalDate =
        minOf(today.minusDays(PAST_DAYS), today.withDayOfMonth(1))

    fun build(
        series: Map<Series, List<Point>>,
        today: LocalDate,
        zone: ZoneId,
        fetchedAt: Instant,
    ): MeuseSnapshot {
        val amayQ = series[Series.AMAY_Q_1H].orEmpty()
        val byDay = amayQ.groupBy { it.time.atZoneSameInstant(zone).toLocalDate() }

        val bars = (PAST_DAYS downTo 0).map { back ->
            val day = today.minusDays(back)
            val values = byDay[day].orEmpty().map { it.value }
            DayBar(
                date = day,
                mean = values.takeIf { it.isNotEmpty() }?.average(),
                hours = values.size,
                isToday = back == 0L,
            )
        }

        val monthStart = today.withDayOfMonth(1)
        val monthToDate = amayQ
            .filter { !it.time.atZoneSameInstant(zone).toLocalDate().isBefore(monthStart) }
            .map { it.value }
            .takeIf { it.isNotEmpty() }
            ?.average()

        val monthNormal = series[Series.AMAY_Q_NORMALE_MOIS].orEmpty()
            .firstOrNull {
                val d = it.time.atZoneSameInstant(zone).toLocalDate()
                d.year == today.year && d.month == today.month
            }?.value

        fun todayValue(id: Series) = series[id].orEmpty()
            .firstOrNull { it.time.atZoneSameInstant(zone).toLocalDate() == today }?.value
        val p15 = todayValue(Series.AMAY_Q_JOUR_P15)
        val med = todayValue(Series.AMAY_Q_JOUR_MEDIANE)
        val p85 = todayValue(Series.AMAY_Q_JOUR_P85)
        val p90 = todayValue(Series.AMAY_Q_JOUR_P90)
        val dayStats = if (p15 != null && med != null && p85 != null && p90 != null) {
            DayStats(p15, med, p85, p90)
        } else {
            null
        }

        return MeuseSnapshot(
            bars = bars,
            monthNormal = monthNormal,
            monthToDate = monthToDate,
            amayFlow = reading(amayQ, relativeThreshold = 0.02),
            ivozLevel = reading(series[Series.IVOZ_H_1H].orEmpty(), absoluteThreshold = 0.02),
            ivozFlow = reading(series[Series.IVOZ_Q_1H].orEmpty(), relativeThreshold = 0.02),
            dayStats = dayStats,
            fetchedAt = fetchedAt,
        )
    }

    /** Dernière valeur + tendance par rapport à la valeur d'il y a 3 heures. */
    internal fun reading(
        points: List<Point>,
        relativeThreshold: Double? = null,
        absoluteThreshold: Double? = null,
    ): Reading? {
        val last = points.lastOrNull() ?: return null
        val ref = points.lastOrNull { !it.time.isAfter(last.time.minusHours(3)) }
        val trend = when {
            ref == null -> Trend.INCONNUE
            else -> {
                val delta = last.value - ref.value
                val threshold = absoluteThreshold ?: (abs(ref.value) * (relativeThreshold ?: 0.0))
                when {
                    delta > threshold -> Trend.HAUSSE
                    delta < -threshold -> Trend.BAISSE
                    else -> Trend.STABLE
                }
            }
        }
        return Reading(last, trend)
    }
}
