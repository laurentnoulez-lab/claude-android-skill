package be.wallonie.intemperies

import be.wallonie.intemperies.domain.DayClassifier
import be.wallonie.intemperies.model.Criteria
import be.wallonie.intemperies.model.DayStatus
import be.wallonie.intemperies.model.Reason
import be.wallonie.intemperies.model.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class DayClassifierTest {
    private val zone = ZoneId.of("Europe/Brussels")
    private val day = LocalDate.of(2026, 11, 17) // mardi
    private val later = day.plusDays(2).atStartOfDay(zone).toInstant()
    private val criteria = Criteria()

    /** Journée complète de mesures à 5 min ; [rainy] = intervalles [début, fin[ avec 0,2 mm / 5 min. */
    private fun samples(vararg rainy: Pair<LocalTime, LocalTime>, mmPer5: Double = 0.2, quality: Int = 40): List<Sample> {
        val start = day.atStartOfDay(zone).toEpochSecond()
        return (0 until 288).map { i ->
            val t = start + i * 300L
            val lt = LocalTime.ofSecondOfDay((i * 300L))
            val wet = rainy.any { (a, b) -> !lt.isBefore(a) && lt.isBefore(b) }
            Sample(t, if (wet) mmPer5 else 0.0, quality)
        }
    }

    private fun temps(value: Double) = (0 until 24).map {
        Sample(day.atStartOfDay(zone).toEpochSecond() + it * 3600L, value, 40)
    }

    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test
    fun dryDayIsGreen() {
        val r = DayClassifier.classify(day, samples(), temps(8.0), criteria, zone, later)
        assertEquals(DayStatus.DRY, r.status)
        assertEquals(0.0, r.rainHours, 1e-9)
        assertEquals(1.0, r.coverage, 1e-9)
        assertEquals(17, r.wetSteps.size) // 07:30–16:00 en pas de 30 min
    }

    @Test
    fun exactlyThreeConsecutiveHoursIsNotIntemperieWhenStrict() {
        val r = DayClassifier.classify(day, samples(t(8) to t(11)), temps(8.0), criteria, zone, later)
        assertEquals(3.0, r.maxConsecutiveRainHours, 1e-9)
        assertEquals(DayStatus.WARNING, r.status) // ≥ 2 h → orange
    }

    @Test
    fun moreThanThreeConsecutiveHoursIsIntemperie() {
        val r = DayClassifier.classify(day, samples(t(8) to t(11, 30)), temps(8.0), criteria, zone, later)
        assertEquals(DayStatus.INTEMPERIE, r.status)
        assertEquals(setOf(Reason.CONSECUTIVE_RAIN), r.reasons)
    }

    @Test
    fun threeHoursIsIntemperieWhenNotStrict() {
        val r = DayClassifier.classify(day, samples(t(8) to t(11)), temps(8.0), criteria.copy(strictComparison = false), zone, later)
        assertEquals(DayStatus.INTEMPERIE, r.status)
    }

    @Test
    fun moreThanFourHoursTotalIsIntemperie() {
        // 2 h + 2 h 30 séparées : aucune série > 3 h mais total 4 h 30 > 4 h.
        val r = DayClassifier.classify(day, samples(t(7, 30) to t(9, 30), t(12) to t(14, 30)), temps(8.0), criteria, zone, later)
        assertEquals(4.5, r.rainHours, 1e-9)
        assertEquals(2.5, r.maxConsecutiveRainHours, 1e-9)
        assertEquals(setOf(Reason.TOTAL_RAIN), r.reasons)
    }

    @Test
    fun rainOutsideWindowIsIgnoredButCountedIn24h() {
        val r = DayClassifier.classify(day, samples(t(17) to t(23)), temps(8.0), criteria, zone, later)
        assertEquals(DayStatus.DRY, r.status)
        assertEquals(0.0, r.windowRainMm, 1e-9)
        assertEquals(72 * 0.2, r.dayRainMm, 1e-6)
    }

    @Test
    fun twoHoursOfRainIsOrange() {
        val r = DayClassifier.classify(day, samples(t(10) to t(12)), temps(8.0), criteria, zone, later)
        assertEquals(DayStatus.WARNING, r.status)
    }

    @Test
    fun wetThresholdIsAnIntensity() {
        // Défaut 1 mm/h → 0,5 mm par pas de 30 min.
        assertEquals(1.0, criteria.wetIntensityMmH, 0.0)
        assertEquals(0.5, criteria.wetStepThresholdMm, 1e-9)
        assertEquals(0.25, criteria.copy(stepMinutes = 15).wetStepThresholdMm, 1e-9)
        assertEquals(0.3, criteria.copy(stepMinutes = 15).effectiveStepThresholdMm, 1e-9)
        // 0,1 mm / 5 min = 0,6 mm / 30 min (1,2 mm/h) : pluvieux.
        val moderate = samples(t(8) to t(12), mmPer5 = 0.1)
        assertEquals(DayStatus.INTEMPERIE, DayClassifier.classify(day, moderate, temps(8.0), criteria, zone, later).status)
        // 0,05 mm / 5 min = 0,3 mm / 30 min (0,6 mm/h) : sous le seuil par défaut…
        val light = samples(t(8) to t(12), mmPer5 = 0.05)
        assertEquals(DayStatus.DRY, DayClassifier.classify(day, light, temps(8.0), criteria, zone, later).status)
        // … mais pluvieux si l'utilisateur abaisse le seuil à 0,5 mm/h.
        assertEquals(DayStatus.INTEMPERIE, DayClassifier.classify(day, light, temps(8.0), criteria.copy(wetIntensityMmH = 0.5), zone, later).status)
    }

    @Test
    fun stepAtExactThresholdCounts() {
        // 5 × 0,1 mm = 0,5 mm sur le pas 09:00–09:30 : compté (≥ 0,5 mm) ; 4 × 0,1 mm : non compté.
        val start = day.atStartOfDay(zone).toEpochSecond()
        val nine = day.atTime(9, 0).atZone(zone).toEpochSecond()
        fun withTips(n: Int) = (0 until 288).map { i ->
            val ts = start + i * 300L
            Sample(ts, if (ts >= nine && ts < nine + n * 300L) 0.1 else 0.0, 40)
        }
        assertEquals(0.5, DayClassifier.classify(day, withTips(5), temps(8.0), criteria, zone, later).rainHours, 1e-9)
        assertEquals(0.0, DayClassifier.classify(day, withTips(4), temps(8.0), criteria, zone, later).rainHours, 1e-9)
    }

    @Test
    fun shortLastStepHasProportionalThreshold() {
        // Pas de 60 min : dernier pas 15:30–16:00 (30 min) → seuil 0,5 mm (et non 1 mm).
        val start = day.atStartOfDay(zone).toEpochSecond()
        val t1530 = day.atTime(15, 30).atZone(zone).toEpochSecond()
        val s = (0 until 288).map { i ->
            val ts = start + i * 300L
            Sample(ts, if (ts >= t1530 && ts < t1530 + 1800) 0.1 else 0.0, 40) // 0,6 mm
        }
        val r = DayClassifier.classify(day, s, temps(8.0), criteria.copy(stepMinutes = 60), zone, later)
        assertEquals(true, r.wetSteps.last())
        assertEquals(0.5, r.rainHours, 1e-9)
    }

    @Test
    fun coldIsInformativeByDefaultAndBlockingWhenChecked() {
        val r1 = DayClassifier.classify(day, samples(), temps(-1.5), criteria, zone, later)
        assertEquals(DayStatus.DRY, r1.status)
        assertTrue(r1.coldReached == true)
        assertEquals(8.5, r1.coldHours!!, 1e-9)
        assertEquals(-1.5, r1.meanTemperatureC!!, 1e-9)
        val r2 = DayClassifier.classify(day, samples(), temps(-1.5), criteria.copy(coldCountsAsIntemperie = true), zone, later)
        assertEquals(DayStatus.INTEMPERIE, r2.status)
        assertEquals(setOf(Reason.COLD), r2.reasons)
    }

    /** Moyennes horaires : [cold] heures (à partir de 08:00) sous 0 °C, le reste à 5 °C. */
    private fun tempsWithColdHours(cold: Int, value: Double = -2.0): List<Sample> {
        val start = day.atStartOfDay(zone).toEpochSecond()
        return (0 until 24).map { h -> Sample(start + h * 3600L, if (h in 8 until 8 + cold) value else 5.0, 200) }
    }

    @Test
    fun coldDurationMustReachMinimum() {
        val c = criteria.copy(coldCountsAsIntemperie = true)
        val two = DayClassifier.classify(day, samples(), tempsWithColdHours(2), c, zone, later)
        assertEquals(2.0, two.coldHours!!, 1e-9)
        assertEquals(DayStatus.DRY, two.status)
        val three = DayClassifier.classify(day, samples(), tempsWithColdHours(3), c, zone, later)
        assertEquals(DayStatus.INTEMPERIE, three.status) // durée ≥ 3 h (défaut)
        val longer = DayClassifier.classify(day, samples(), tempsWithColdHours(3), c.copy(coldMinHours = 3.5), zone, later)
        assertEquals(DayStatus.DRY, longer.status)
    }

    @Test
    fun heatThresholdIsBlockingWhenChecked() {
        val start = day.atStartOfDay(zone).toEpochSecond()
        // 11:00–16:00 à 33 °C (5 h), sinon 25 °C.
        val hot = (0 until 24).map { h -> Sample(start + h * 3600L, if (h in 11..15) 33.0 else 25.0, 40) }
        val info = DayClassifier.classify(day, samples(), hot, criteria, zone, later)
        assertEquals(5.0, info.heatHours!!, 1e-9)
        assertTrue(info.heatReached == true)
        assertEquals(DayStatus.DRY, info.status)
        assertEquals(33.0, info.maxTemperatureC!!, 1e-9)
        val blocking = DayClassifier.classify(day, samples(), hot, criteria.copy(heatCountsAsIntemperie = true), zone, later)
        assertEquals(setOf(Reason.HEAT), blocking.reasons)
        val higher = DayClassifier.classify(day, samples(), hot, criteria.copy(heatCountsAsIntemperie = true, heatThresholdC = 35.0), zone, later)
        assertEquals(DayStatus.DRY, higher.status)
    }

    @Test
    fun defaultTemperatureCriteria() {
        val d = Criteria()
        assertEquals(0.0, d.coldThresholdC, 0.0)
        assertEquals(32.0, d.heatThresholdC, 0.0)
        assertEquals(3.0, d.coldMinHours, 0.0)
        assertEquals(3.0, d.heatMinHours, 0.0)
        assertFalse(d.coldCountsAsIntemperie || d.heatCountsAsIntemperie)
        // Exactement 32 °C ne dépasse pas le seuil haut (« supérieure à »).
        val start = day.atStartOfDay(zone).toEpochSecond()
        val at32 = (0 until 24).map { h -> Sample(start + h * 3600L, 32.0, 40) }
        assertEquals(0.0, DayClassifier.classify(day, samples(), at32, d, zone, later).heatHours!!, 1e-9)
    }

    @Test
    fun rainAndTemperatureReasonsCombine() {
        val c = criteria.copy(coldCountsAsIntemperie = true)
        val r = DayClassifier.classify(day, samples(t(8) to t(11, 30)), tempsWithColdHours(5), c, zone, later)
        assertEquals(setOf(Reason.CONSECUTIVE_RAIN, Reason.COLD), r.reasons)
    }

    @Test
    fun temperatureIsTimeWeightedOverWindow() {
        // 07:00–08:00 à 10 °C (ne compte que 30 min), 08:00–16:00 à 0 °C (8 h) → 5/8,5 ≈ 0,588 °C
        val start = day.atStartOfDay(zone).toEpochSecond()
        val temp = (0 until 24).map { Sample(start + it * 3600L, if (it == 7) 10.0 else 0.0, 40) }
        val r = DayClassifier.classify(day, samples(), temp, criteria, zone, later)
        assertEquals(10.0 * 0.5 / 8.5, r.meanTemperatureC!!, 1e-9)
    }

    @Test
    fun missingDataIsGrey() {
        val s = samples().filterIndexed { i, _ -> i % 2 == 0 } // 50 % de couverture
        val r = DayClassifier.classify(day, s, emptyList(), criteria, zone, later)
        assertEquals(DayStatus.NO_DATA, r.status)
        assertNull(r.meanTemperatureC)
    }

    @Test
    fun provisionalFlagFollowsSpwQualityCode() {
        assertFalse(DayClassifier.classify(day, samples(quality = 40), temps(5.0), criteria, zone, later).provisional)
        assertTrue(DayClassifier.classify(day, samples(quality = 200), temps(5.0), criteria, zone, later).provisional)
    }

    @Test
    fun todayBeforeEndOfWindowIsInProgressAndFutureIsFuture() {
        val noon = day.atTime(12, 0).atZone(zone).toInstant()
        val partial = samples().filter { it.epochSec < noon.epochSecond }
        assertEquals(DayStatus.IN_PROGRESS, DayClassifier.classify(day, partial, emptyList(), criteria, zone, noon).status)
        val yesterdayNoon = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
        assertEquals(DayStatus.FUTURE, DayClassifier.classify(day, emptyList(), emptyList(), criteria, zone, yesterdayNoon).status)
    }

    @Test
    fun hourlyStepWithShortLastStep() {
        // Pas de 60 min : 07:30-08:30 … 14:30-15:30, 15:30-16:00 (30 min).
        val r = DayClassifier.classify(day, samples(t(15, 30) to t(16)), temps(8.0), criteria.copy(stepMinutes = 60), zone, later)
        assertEquals(9, r.wetSteps.size)
        assertEquals(0.5, r.rainHours, 1e-9)
    }
}
