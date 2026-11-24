package be.wallonie.intemperies

import be.wallonie.intemperies.data.remote.KiwisClient
import be.wallonie.intemperies.domain.StationMatcher
import be.wallonie.intemperies.model.Commune
import be.wallonie.intemperies.model.StationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class KiwisParserTest {
    // Extrait réel (format) de getTimeseriesValues — hydrometrie.wallonie.be, Wavre, pluie 1 h.
    private val values = """[{"ts_id": "233979010","rows": "3","columns":"Timestamp,Value,Quality Code", "data": [
        ["2026-11-15T16:00:00.000+01:00",1.10,40],["2026-11-15T17:00:00.000+01:00",2.70,200],["2026-11-15T18:00:00.000+01:00",null,255]]}]"""

    @Test
    fun parsesValuesQualityAndNulls() {
        val s = KiwisClient.parseValues(values)
        assertEquals(3, s.size)
        assertEquals(OffsetDateTime.parse("2026-11-15T16:00:00+01:00").toEpochSecond(), s[0].epochSec)
        assertEquals(1.1, s[0].value, 1e-9)
        assertEquals(200, s[1].quality)
        assertTrue(s[2].value.isNaN())
    }

    @Test
    fun parsesStationListAndDropsStaleOnes() {
        val body = """[["station_name","station_no","station_latitude","station_longitude","ts_id","site_no","from","to"],
            ["WAVRE","1043","50.7281559765013","4.63610353464494","233989010","DGH","2016-06-01T01:00:00.000+02:00","2026-11-22T09:40:00.000+01:00"],
            ["CINEY","8157","50.2914","5.1133","251477010","DGH","1998-01-01T00:00:00.000+01:00","2022-03-31T10:00:00.000+02:00"]]"""
        val list = KiwisClient.parseStations(body, StationKind.RAIN, OffsetDateTime.parse("2026-11-22T10:00:00+01:00"))
        assertEquals(listOf("1043"), list.map { it.stationNo })
    }

    @Test
    fun nearestStationAndOverride() {
        val body = """[["station_name","station_no","station_latitude","station_longitude","ts_id","site_no","to"],
            ["WAVRE","1043","50.7281559765013","4.63610353464494","1","DGH","2026-11-22T09:40:00.000+01:00"],
            ["ARLON","9596","49.681906686779","5.82380957359689","2","DGH","2026-11-22T09:40:00.000+01:00"]]"""
        val stations = KiwisClient.parseStations(body, StationKind.RAIN, OffsetDateTime.parse("2026-11-22T10:00:00+01:00"))
        val wavre = Commune("25112", "Wavre", "Brabant wallon", 50.71582, 4.59324)
        val auto = StationMatcher.assign(wavre, stations, null)!!
        assertEquals("1043", auto.station.stationNo)
        assertTrue(auto.distanceKm < 5)
        val manual = StationMatcher.assign(wavre, stations, "9596")!!
        assertEquals("9596", manual.station.stationNo)
        assertTrue(manual.manual)
        assertEquals(null, StationMatcher.assign(wavre, stations, null, maxDistanceKm = 1.0))
    }
}
