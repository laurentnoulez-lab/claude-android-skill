package be.wallonie.intemperies.domain

import be.wallonie.intemperies.model.Criteria
import be.wallonie.intemperies.model.DayReport
import be.wallonie.intemperies.model.DayStatus
import be.wallonie.intemperies.model.Reason
import be.wallonie.intemperies.model.Sample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Qualifie une journée à partir des mesures de pluie (pas de 5 min) et de température (pas horaire).
 *
 * Méthode (paramétrable via [Criteria]) :
 *  1. La plage horaire [windowStart ; windowEnd[ est découpée en pas de `stepMinutes`
 *     alignés sur windowStart (le dernier pas peut être plus court).
 *  2. Un pas est « pluvieux » si son cumul ≥ intensité minimale (mm/h) × durée du pas (`wetStepThresholdMm`).
 *  3. Durée de pluie = somme des durées des pas pluvieux ;
 *     pluie consécutive max = plus longue suite ininterrompue de pas pluvieux.
 *  4. Rouge si pluie consécutive > seuil OU durée totale > seuil (≥ si comparaison non stricte),
 *     ou, si l'option correspondante est cochée, si la température horaire est restée sous le seuil
 *     bas (gel) ou au-dessus du seuil haut (canicule) pendant au moins la durée paramétrée.
 *     Orange si durée totale ≥ seuil d'avertissement. Vert sinon.
 *  5. Gris si la part de données disponibles dans la plage est < `minCoverage`.
 */
object DayClassifier {
    const val RAIN_STEP_SEC = 300L
    const val TEMPERATURE_STEP_SEC = 3600L
    private const val EPS = 1e-9

    /**
     * Code qualité SPW : les données validées ont un code < 200 (mentions légales hydrometrie.wallonie.be).
     * Seule la pluie (critère déterminant) alimente l'indicateur « provisoire » : les séries de température
     * SPW restent au code 200 (constat du 22/11/2026).
     */
    const val VALIDATED_QUALITY_LIMIT = 200

    fun classify(
        date: LocalDate,
        rain: List<Sample>,
        temperature: List<Sample>,
        criteria: Criteria,
        zone: ZoneId,
        now: Instant,
        temperatureAvailable: Boolean = true,
    ): DayReport {
        val windowStart = date.atTime(criteria.windowStart).atZone(zone).toEpochSecond()
        val windowEnd = date.atTime(criteria.windowEnd).atZone(zone).toEpochSecond()
        val dayStart = date.atStartOfDay(zone).toEpochSecond()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val nowSec = now.epochSecond
        val holiday = BelgianHolidays.nameOf(date)

        if (nowSec < dayStart) {
            return empty(date, DayStatus.FUTURE, holiday)
        }

        val stepSec = criteria.stepMinutes.coerceAtLeast(5) * 60L
        val stepBounds = buildList {
            var s = windowStart
            while (s < windowEnd) {
                add(s to minOf(s + stepSec, windowEnd))
                s += stepSec
            }
        }
        val stepSums = DoubleArray(stepBounds.size)
        val stepCounts = IntArray(stepBounds.size)
        var windowRain = 0.0
        var dayRain = 0.0
        var samplesInWindow = 0
        var provisional = false
        for (sample in rain) {
            if (sample.value.isNaN()) continue
            if (sample.epochSec in dayStart until dayEnd) dayRain += sample.value
            if (sample.epochSec in windowStart until windowEnd) {
                windowRain += sample.value
                samplesInWindow++
                if (sample.quality >= VALIDATED_QUALITY_LIMIT) provisional = true
                val idx = ((sample.epochSec - windowStart) / stepSec).toInt()
                if (idx in stepSums.indices) {
                    stepSums[idx] += sample.value
                    stepCounts[idx]++
                }
            }
        }
        // Seuil = intensité minimale × durée réelle du pas (un dernier pas plus court a un seuil proportionnel).
        val wet = stepSums.indices.map { i ->
            val (s, e) = stepBounds[i]
            stepSums[i] + EPS >= criteria.wetIntensityMmH * (e - s) / 3600.0
        }
        var rainSec = 0L
        var maxRunSec = 0L
        var runSec = 0L
        stepBounds.forEachIndexed { i, (s, e) ->
            if (wet[i]) {
                rainSec += e - s
                runSec += e - s
                if (runSec > maxRunSec) maxRunSec = runSec
            } else {
                runSec = 0
            }
        }
        val rainHours = rainSec / 3600.0
        val maxConsecutive = maxRunSec / 3600.0

        // Couverture : rapportée à la partie déjà écoulée de la plage.
        val effectiveEnd = minOf(windowEnd, maxOf(windowStart, nowSec))
        val expected = ((effectiveEnd - windowStart) / RAIN_STEP_SEC).toInt()
        val coverage = if (expected <= 0) 0.0 else (samplesInWindow.toDouble() / expected).coerceAtMost(1.0)

        // Température : chaque moyenne horaire est pondérée par son recouvrement avec la plage.
        var weighted = 0.0
        var weight = 0L
        var coldSec = 0L
        var heatSec = 0L
        val tempPoints = ArrayList<Sample>()
        var minT = Double.POSITIVE_INFINITY
        var maxT = Double.NEGATIVE_INFINITY
        if (temperatureAvailable) {
            for (t in temperature) {
                if (t.value.isNaN()) continue
                val overlap = minOf(t.epochSec + TEMPERATURE_STEP_SEC, windowEnd) - maxOf(t.epochSec, windowStart)
                if (overlap > 0) {
                    weighted += t.value * overlap
                    weight += overlap
                    if (t.value < criteria.coldThresholdC - EPS) coldSec += overlap
                    if (t.value > criteria.heatThresholdC + EPS) heatSec += overlap
                    tempPoints += t
                    minT = minOf(minT, t.value)
                    maxT = maxOf(maxT, t.value)
                }
            }
        }
        val tempKnown = weight > 0 && (weight >= (windowEnd - windowStart) / 2 || coldSec > 0 || heatSec > 0)
        val meanTemp = if (weight >= (windowEnd - windowStart) / 2) weighted / weight else null
        val coldHours = if (tempKnown) coldSec / 3600.0 else null
        val heatHours = if (tempKnown) heatSec / 3600.0 else null
        val coldReached = coldHours?.let { it + EPS >= criteria.coldMinHours }
        val heatReached = heatHours?.let { it + EPS >= criteria.heatMinHours }

        val reasons = buildSet {
            if (exceeds(maxConsecutive, criteria.consecutiveRainHours, criteria.strictComparison)) add(Reason.CONSECUTIVE_RAIN)
            if (exceeds(rainHours, criteria.totalRainHours, criteria.strictComparison)) add(Reason.TOTAL_RAIN)
            if (criteria.coldCountsAsIntemperie && coldReached == true) add(Reason.COLD)
            if (criteria.heatCountsAsIntemperie && heatReached == true) add(Reason.HEAT)
        }
        val inProgress = nowSec < windowEnd
        val status = when {
            reasons.isNotEmpty() -> DayStatus.INTEMPERIE
            inProgress -> DayStatus.IN_PROGRESS
            coverage + EPS < criteria.minCoverage -> DayStatus.NO_DATA
            rainHours + EPS >= criteria.warningRainHours -> DayStatus.WARNING
            else -> DayStatus.DRY
        }
        return DayReport(
            date = date,
            status = status,
            windowRainMm = windowRain,
            dayRainMm = dayRain,
            rainHours = rainHours,
            maxConsecutiveRainHours = maxConsecutive,
            meanTemperatureC = meanTemp,
            minTemperatureC = minT.takeIf { weight > 0 },
            maxTemperatureC = maxT.takeIf { weight > 0 },
            coldHours = coldHours,
            heatHours = heatHours,
            coldReached = coldReached,
            heatReached = heatReached,
            reasons = reasons,
            coverage = coverage,
            provisional = provisional,
            wetSteps = wet,
            stepRainMm = stepSums.indices.map { if (stepCounts[it] == 0) Double.NaN else stepSums[it] },
            temperaturePoints = tempPoints.sortedBy { it.epochSec },
            holidayName = holiday,
        )
    }

    fun exceeds(value: Double, threshold: Double, strict: Boolean): Boolean =
        if (strict) value > threshold + EPS else value + EPS >= threshold

    private fun empty(date: LocalDate, status: DayStatus, holiday: String?) = DayReport(
        date = date, status = status, windowRainMm = 0.0, dayRainMm = 0.0, rainHours = 0.0,
        maxConsecutiveRainHours = 0.0, meanTemperatureC = null, minTemperatureC = null, maxTemperatureC = null,
        coldHours = null, heatHours = null, coldReached = null, heatReached = null,
        reasons = emptySet(), coverage = 0.0, provisional = false, wetSteps = emptyList(),
        stepRainMm = emptyList(), temperaturePoints = emptyList(), holidayName = holiday,
    )
}
