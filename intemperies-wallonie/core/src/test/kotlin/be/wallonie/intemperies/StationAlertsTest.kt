package be.wallonie.intemperies

import be.wallonie.intemperies.model.Commune
import be.wallonie.intemperies.model.Criteria
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.model.Station
import be.wallonie.intemperies.model.StationAssignment
import be.wallonie.intemperies.model.StationKind
import be.wallonie.intemperies.ui.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class StationAlertsTest {
    private val commune = Commune("54007", "Mouscron", "Hainaut", 50.74, 3.25)
    private val now = 1_800_000_000L
    private fun st(kind: StationKind) = StationAssignment(Station("1", "X", 0.0, 0.0, "t", kind, "DGH"), 5.0, false)
    private fun report(rain: Boolean, temp: Boolean, lastTemp: Long?) = MonthReport(
        commune, YearMonth.of(2026, 11), if (rain) st(StationKind.RAIN) else null, if (temp) st(StationKind.TEMPERATURE) else null,
        emptyList(), Criteria(), lastRainDataEpochSec = now, lastTemperatureDataEpochSec = lastTemp,
    )

    @Test
    fun noAlertWhenStationsReportRecently() = assertEquals(emptyList<String>(), Fmt.stationAlerts(report(true, true, now - 3600), now))

    @Test
    fun missingTemperatureStationSuggestsWiderRadius() {
        val a = Fmt.stationAlerts(report(true, false, null), now)
        assertEquals(1, a.size)
        assertTrue(a[0].contains("40 km") && a[0].contains("augmentez la distance") && a[0].contains("manuellement"))
    }

    @Test
    fun missingRainStationAndSilentTemperatureStation() {
        val a = Fmt.stationAlerts(report(false, true, now - 8 * 3600), now)
        assertEquals(2, a.size)
        assertTrue(a[0].contains("pluviomètre") && a[1].contains("sans mesure"))
    }
}
