package be.meuse.engis

import be.meuse.engis.domain.CarnassierRules
import be.meuse.engis.domain.FishingStatus
import be.meuse.engis.domain.FlowClass
import be.meuse.engis.domain.Sun
import be.meuse.engis.ui.FisherMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RegulationTest {

    private val zone = ZoneId.of("Europe/Brussels")
    private fun at(iso: String) = ZonedDateTime.parse("$iso+01:00[Europe/Brussels]").withZoneSameLocal(zone)
    private fun t(hhmmss: String) = LocalTime.parse(hhmmss)

    private fun assertNear(expected: LocalTime, actual: ZonedDateTime, seconds: Long = 60) {
        val diff = Duration.between(expected, actual.toLocalTime()).abs()
        assertTrue("attendu ≈ $expected, obtenu ${actual.toLocalTime()}", diff.seconds <= seconds)
    }

    /** Valeurs de référence calculées avec la bibliothèque Python « astral » 3.x. */
    @Test
    fun sunTimes_matchReferenceLibrary() {
        val cases = listOf(
            Triple(LocalDate.of(2026, 11, 22), Sun.ENGIS, t("08:04:35") to t("16:43:55")),
            Triple(LocalDate.of(2026, 6, 6), Sun.ENGIS, t("05:28:44") to t("21:45:56")),
            Triple(LocalDate.of(2026, 3, 29), Sun.ENGIS, t("07:21:42") to t("20:05:34")), // passage à l'heure d'été
            Triple(LocalDate.of(2026, 12, 21), Sun.ENGIS, t("08:37:22") to t("16:35:33")),
            Triple(LocalDate.of(2026, 11, 22), Sun.UCCLE, t("08:09:37") to t("16:47:13")),
            Triple(LocalDate.of(2027, 1, 31), Sun.UCCLE, t("08:20:47") to t("17:31:45")),
        )
        cases.forEach { (date, place, expected) ->
            val s = Sun.times(date, place, zone)
            assertNear(expected.first, s.sunrise)
            assertNear(expected.second, s.sunset)
        }
    }

    @Test
    fun dayWindow_isOneHourAroundSun_prudentBetweenUccleAndEngis() {
        val (start, end) = CarnassierRules.dayWindow(LocalDate.of(2026, 11, 22), zone)
        // lever Uccle 08:09:37 − 1 h + 1 min de marge, arrondi à la minute supérieure
        assertNear(t("07:11:00"), start)
        // coucher Engis 16:43:55 + 1 h − 1 min de marge, arrondi à la minute inférieure
        assertNear(t("17:43:00"), end)
        // Toujours du côté prudent par rapport à la référence (sans marge) :
        assertTrue(start.toLocalTime() > t("07:09:37"))
        assertTrue(end.toLocalTime() < t("17:43:55"))
        assertEquals(0, start.second)
        assertEquals(0, end.second)
    }

    @Test
    fun firstSaturdayOfJune() {
        assertEquals(LocalDate.of(2026, 6, 6), CarnassierRules.firstSaturdayOfJune(2026))
        assertEquals(LocalDate.of(2027, 6, 5), CarnassierRules.firstSaturdayOfJune(2027))
        assertEquals(LocalDate.of(2030, 6, 1), CarnassierRules.firstSaturdayOfJune(2030))
    }

    @Test
    fun season_openFromFirstSaturdayOfJuneTo31January() {
        assertTrue(CarnassierRules.isSeasonOpen(LocalDate.of(2026, 6, 6)))
        assertFalse(CarnassierRules.isSeasonOpen(LocalDate.of(2026, 6, 5)))
        assertTrue(CarnassierRules.isSeasonOpen(LocalDate.of(2026, 9, 30)))
        assertTrue(CarnassierRules.isSeasonOpen(LocalDate.of(2026, 10, 1)))
        assertTrue(CarnassierRules.isSeasonOpen(LocalDate.of(2027, 1, 31)))
        assertFalse(CarnassierRules.isSeasonOpen(LocalDate.of(2027, 2, 1)))
        assertFalse(CarnassierRules.isSeasonOpen(LocalDate.of(2027, 3, 15)))
    }

    @Test
    fun status_dayNightAndSeason() {
        val day = CarnassierRules.status(at("2026-11-22T10:00:00"))
        assertTrue(day is FishingStatus.Open)
        assertNear(t("17:43:00"), (day as FishingStatus.Open).until)

        val early = CarnassierRules.status(at("2026-11-22T06:00:00")) as FishingStatus.Closed
        assertEquals(FishingStatus.Reason.NUIT, early.reason)
        assertEquals(LocalDate.of(2026, 11, 22), early.reopensAt.toLocalDate())

        val evening = CarnassierRules.status(at("2026-11-22T19:30:00")) as FishingStatus.Closed
        assertEquals(FishingStatus.Reason.NUIT, evening.reason)
        assertEquals(LocalDate.of(2026, 11, 23), evening.reopensAt.toLocalDate())
        assertNear(t("07:12:00"), evening.reopensAt)

        val lastDay = CarnassierRules.status(at("2027-01-31T12:00:00")) as FishingStatus.Open
        assertTrue(lastDay.seasonLastDay)
        val afterLast = CarnassierRules.status(at("2027-01-31T20:00:00")) as FishingStatus.Closed
        assertEquals(FishingStatus.Reason.FERMETURE, afterLast.reason)
        assertEquals(LocalDate.of(2027, 6, 5), afterLast.reopensAt.toLocalDate())
        assertNear(t("04:34:00"), afterLast.reopensAt)

        val march = CarnassierRules.status(at("2026-03-15T12:00:00")) as FishingStatus.Closed
        assertEquals(FishingStatus.Reason.FERMETURE, march.reason)
        assertEquals(LocalDate.of(2026, 6, 6), march.reopensAt.toLocalDate())

        assertTrue(CarnassierRules.status(at("2026-06-05T12:00:00")) is FishingStatus.Closed)
        assertTrue(CarnassierRules.status(at("2026-06-06T12:00:00")) is FishingStatus.Open)
    }

    @Test
    fun fisherMood() {
        val open = CarnassierRules.status(at("2026-11-22T10:00:00"))
        val closed = CarnassierRules.status(at("2026-11-22T23:00:00"))
        assertEquals(FisherMood.FISH, FisherMood.of(open, FlowClass.NORMAL))
        assertEquals(FisherMood.NONE, FisherMood.of(open, FlowClass.ELEVE))
        assertEquals(FisherMood.NONE, FisherMood.of(open, null))
        assertEquals(FisherMood.SLEEP, FisherMood.of(closed, FlowClass.NORMAL))
        assertEquals(FisherMood.SLEEP, FisherMood.of(closed, FlowClass.FAIBLE))
    }
}
