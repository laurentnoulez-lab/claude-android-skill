package be.meuse.engis

import be.meuse.engis.data.KiwisClient
import be.meuse.engis.data.Stations
import be.meuse.engis.domain.Point
import be.meuse.engis.domain.SnapshotBuilder
import be.meuse.engis.domain.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

class SnapshotBuilderTest {

    private val zone = ZoneId.of("Europe/Brussels")

    private fun p(iso: String, v: Double) = Point(OffsetDateTime.parse(iso), v)

    @Test
    fun parse_ignoresNullValues_andKeepsSeriesById() {
        val json = """
            [{"ts_id": "245564010","rows": "3","columns":"Timestamp,Value,Quality Code",
              "data": [["2026-11-20T00:00:00.000+01:00",321.493,200],
                       ["2026-11-21T00:00:00.000+01:00",448.014,200],
                       ["2026-11-22T00:00:00.000+01:00",null,-1]]},
             {"ts_id": "245388010","rows": "1","columns":"Timestamp,Value",
              "data": [["2026-11-22T08:20:00.000+01:00",1.521]]}]
        """.trimIndent()
        val series = KiwisClient.parse(json)
        assertEquals(2, series["245564010"]!!.size)
        assertEquals(1.521, series["245388010"]!!.single().value, 1e-9)
    }

    @Test
    fun dailyMean_isAverageOfHourlyValues_inLocalDay() {
        // 2 heures le 21/11 (heure belge), 1 heure le 22/11 ; 23:00Z le 21 = 00:00 le 22 à Bruxelles
        val hourly = listOf(
            p("2026-11-21T10:00:00+01:00", 100.0),
            p("2026-11-21T11:00:00+01:00", 200.0),
            p("2026-11-21T23:00:00Z", 300.0),
        )
        val s = SnapshotBuilder.build(
            mapOf(Stations.AMAY_Q_1H to hourly),
            today = LocalDate.of(2026, 11, 22),
            zone = zone,
            fetchedAt = Instant.EPOCH,
        )
        assertEquals(8, s.bars.size)
        assertEquals(LocalDate.of(2026, 11, 15), s.bars.first().date)
        assertTrue(s.bars.last().isToday)
        assertEquals(300.0, s.bars.last().mean!!, 1e-9)
        assertEquals(150.0, s.bars[6].mean!!, 1e-9)
        assertEquals(2, s.bars[6].hours)
        assertNull(s.bars[0].mean)
        assertEquals(200.0, s.monthToDate!!, 1e-9)
    }

    @Test
    fun monthNormal_picksCurrentMonth() {
        val normals = listOf(
            p("2026-11-01T00:00:00.000+01:00", 190.636),
            p("2026-12-01T00:00:00.000+01:00", 328.643),
        )
        val s = SnapshotBuilder.build(
            mapOf(Stations.AMAY_Q_NORMALE_MOIS to normals),
            today = LocalDate.of(2026, 11, 22),
            zone = zone,
            fetchedAt = Instant.EPOCH,
        )
        assertEquals(190.636, s.monthNormal!!, 1e-9)
    }

    @Test
    fun monthToDate_excludesPreviousMonth() {
        val hourly = listOf(
            p("2026-10-31T12:00:00+01:00", 1000.0),
            p("2026-11-01T12:00:00+01:00", 100.0),
            p("2026-11-03T12:00:00+01:00", 300.0),
        )
        val s = SnapshotBuilder.build(
            mapOf(Stations.AMAY_Q_1H to hourly),
            today = LocalDate.of(2026, 11, 3),
            zone = zone,
            fetchedAt = Instant.EPOCH,
        )
        assertEquals(200.0, s.monthToDate!!, 1e-9)
        assertEquals(LocalDate.of(2026, 10, 27), SnapshotBuilder.windowStart(LocalDate.of(2026, 11, 3)))
    }

    @Test
    fun trend_comparesWithValueThreeHoursBefore() {
        val rising = listOf(
            p("2026-11-22T05:00:00+01:00", 300.0),
            p("2026-11-22T06:00:00+01:00", 305.0),
            p("2026-11-22T08:00:00+01:00", 320.0),
        )
        assertEquals(Trend.HAUSSE, SnapshotBuilder.reading(rising, relativeThreshold = 0.02)!!.trend)
        val flat = listOf(p("2026-11-22T05:00:00+01:00", 1.52), p("2026-11-22T08:00:00+01:00", 1.53))
        assertEquals(Trend.STABLE, SnapshotBuilder.reading(flat, absoluteThreshold = 0.02)!!.trend)
        val single = listOf(p("2026-11-22T08:00:00+01:00", 1.53))
        assertEquals(Trend.INCONNUE, SnapshotBuilder.reading(single, absoluteThreshold = 0.02)!!.trend)
    }
}

class FlowClassTest {
    private val stats = be.meuse.engis.domain.DayStats(p15 = 65.751, median = 166.555, p85 = 329.895, p90 = 379.635)

    @Test
    fun classes_followSpwDailyPercentiles() {
        assertEquals(be.meuse.engis.domain.FlowClass.FAIBLE, be.meuse.engis.domain.FlowClass.of(40.0, stats))
        assertEquals(be.meuse.engis.domain.FlowClass.NORMAL, be.meuse.engis.domain.FlowClass.of(312.49, stats))
        assertEquals(be.meuse.engis.domain.FlowClass.FORT, be.meuse.engis.domain.FlowClass.of(350.0, stats))
        assertEquals(be.meuse.engis.domain.FlowClass.TRES_FORT, be.meuse.engis.domain.FlowClass.of(448.0, stats))
    }

    @Test
    fun snapshot_readsTodayPercentiles_fromRealResponse() {
        val json = javaClass.getResource("/kiwis_2026-11-22.json")!!.readText()
        val s = SnapshotBuilder.build(
            KiwisClient.parse(json),
            today = LocalDate.of(2026, 11, 22),
            zone = ZoneId.of("Europe/Brussels"),
            fetchedAt = Instant.EPOCH,
        )
        assertEquals(stats, s.dayStats)
        assertEquals(190.636, s.monthNormal!!, 1e-9)
        assertEquals(be.meuse.engis.domain.FlowClass.NORMAL, s.flowClass)
    }
}
