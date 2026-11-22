package be.meuse.engis.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Lever / coucher du soleil (algorithme NOAA, réfraction standard : zénith 90,833°).
 * Précision de l'ordre de la minute aux latitudes belges.
 */
object Sun {

    data class Place(val name: String, val lat: Double, val lon: Double)

    /** Pont d'Engis (lieu de pêche). */
    val ENGIS = Place("Engis", 50.5795, 5.4005)

    /** Uccle : référence de l'IRM pour les éphémérides publiées en Belgique. */
    val UCCLE = Place("Uccle", 50.7986, 4.3586)

    data class Times(val sunrise: ZonedDateTime, val sunset: ZonedDateTime)

    fun times(date: LocalDate, place: Place, zone: ZoneId): Times {
        val midnightUtc = date.atStartOfDay(ZoneId.of("UTC"))
        fun at(minutesUtc: Double) = midnightUtc.plusSeconds((minutesUtc * 60).toLong()).withZoneSameInstant(zone)
        return Times(
            sunrise = at(eventMinutesUtc(date, place, rising = true)),
            sunset = at(eventMinutesUtc(date, place, rising = false)),
        )
    }

    /** Minutes après minuit UTC ; deux itérations (recalcul au moment approximatif de l'événement). */
    private fun eventMinutesUtc(date: LocalDate, p: Place, rising: Boolean): Double {
        var minutes = 720.0
        repeat(3) {
            val jd = date.toEpochDay() + 2440587.5 + minutes / 1440.0
            minutes = event(jd, p, rising)
        }
        return minutes
    }

    private fun event(jd: Double, p: Place, rising: Boolean): Double {
        val t = (jd - 2451545.0) / 36525.0
        val l0 = norm360(280.46646 + t * (36000.76983 + t * 0.0003032))
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(rad(2 * m)) * (0.019993 - 0.000101 * t) + sin(rad(3 * m)) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val appLong = trueLong - 0.00569 - 0.00478 * sin(rad(omega))
        val meanObliq = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliq = meanObliq + 0.00256 * cos(rad(omega))
        val decl = deg(asin(sin(rad(obliq)) * sin(rad(appLong))))
        val y = tan(rad(obliq / 2)).let { it * it }
        val eqTime = 4 * deg(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) + 4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m)),
        )
        val ha = deg(
            acos(cos(rad(90.833)) / (cos(rad(p.lat)) * cos(rad(decl))) - tan(rad(p.lat)) * tan(rad(decl))),
        )
        val solarNoon = 720 - 4 * p.lon - eqTime
        return if (rising) solarNoon - 4 * ha else solarNoon + 4 * ha
    }

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm360(x: Double) = x - 360.0 * floor(x / 360.0)
}

/**
 * Réglementation wallonne de la pêche appliquée aux carnassiers (brochet, sandre, perche, aspe :
 * groupe 2 de l'annexe 2) dans la Meuse (zone d'eaux calmes, annexe 3).
 * Source : AGW du 8 décembre 2016 relatif aux conditions d'ouverture et aux modalités d'exercice
 * de la pêche, version consolidée en vigueur depuis le 01/01/2025 (Wallex).
 *
 * - Art. 1er, 6° : pêche de jour = d'1 h avant l'heure officielle du lever du soleil
 *   à 1 h après l'heure officielle du coucher du soleil.
 * - Art. 5, §1er : pêche de nuit des espèces de l'annexe 2 interdite (sauf carpe commune).
 * - Art. 4, 2°, c) et d) : groupe 2 ouvert du 1er samedi de juin au 30 septembre (toutes zones)
 *   et du 1er octobre au 31 janvier (eaux calmes et mixtes).
 */
object CarnassierRules {

    /** L'arrêté ne précise pas le lieu de référence : on retient l'intervalle le plus court Uccle / Engis. */
    private val PLACES = listOf(Sun.ENGIS, Sun.UCCLE)

    fun firstSaturdayOfJune(year: Int): LocalDate =
        LocalDate.of(year, 6, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.SATURDAY))

    /** Saison ouverte : du 1er samedi de juin au 31 janvier suivant (inclus). */
    fun isSeasonOpen(d: LocalDate): Boolean =
        d.monthValue == 1 || !d.isBefore(firstSaturdayOfJune(d.year))

    /** Plage horaire légale de pêche de jour pour une date (prudente : lever le plus tardif, coucher le plus tôt). */
    fun dayWindow(d: LocalDate, zone: ZoneId): Pair<ZonedDateTime, ZonedDateTime> {
        val times = PLACES.map { Sun.times(d, it, zone) }
        val sunrise = times.maxOf { it.sunrise }
        val sunset = times.minOf { it.sunset }
        // Marge de sécurité d'1 minute (l'algorithme diffère d'environ ±20 s des éphémérides de référence),
        // puis arrondi prudent à la minute : début vers le haut, fin vers le bas.
        val start = sunrise.minusHours(1).plusMinutes(1).let { t ->
            val m = t.withSecond(0).withNano(0)
            if (m == t) m else m.plusMinutes(1)
        }
        val end = sunset.plusHours(1).minusMinutes(1).withSecond(0).withNano(0)
        return start to end
    }

    fun status(now: ZonedDateTime): FishingStatus {
        val zone = now.zone
        val today = now.toLocalDate()
        if (!isSeasonOpen(today)) return closedSeason(today, zone)
        val (start, end) = dayWindow(today, zone)
        return when {
            now.isBefore(start) -> FishingStatus.Closed(FishingStatus.Reason.NUIT, reopensAt = start)
            !now.isAfter(end) -> FishingStatus.Open(
                until = end,
                seasonLastDay = today.monthValue == 1 && today.dayOfMonth == 31,
            )
            else -> {
                val tomorrow = today.plusDays(1)
                if (isSeasonOpen(tomorrow)) {
                    FishingStatus.Closed(FishingStatus.Reason.NUIT, reopensAt = dayWindow(tomorrow, zone).first)
                } else {
                    closedSeason(tomorrow, zone)
                }
            }
        }
    }

    private fun closedSeason(d: LocalDate, zone: ZoneId): FishingStatus.Closed {
        val reopenDay = firstSaturdayOfJune(d.year)
        return FishingStatus.Closed(FishingStatus.Reason.FERMETURE, reopensAt = dayWindow(reopenDay, zone).first)
    }

    /** Prochain instant où le statut change (pour rafraîchir le widget au bon moment). */
    fun nextChange(now: ZonedDateTime): ZonedDateTime = when (val s = status(now)) {
        is FishingStatus.Open -> s.until.plusMinutes(1)
        is FishingStatus.Closed -> s.reopensAt
    }
}

sealed interface FishingStatus {
    enum class Reason { NUIT, FERMETURE }

    data class Open(val until: ZonedDateTime, val seasonLastDay: Boolean) : FishingStatus
    data class Closed(val reason: Reason, val reopensAt: ZonedDateTime) : FishingStatus
}
