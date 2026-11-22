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
)
