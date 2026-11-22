package be.wallonie.intemperies

import be.wallonie.intemperies.data.remote.KiwisClient
import be.wallonie.intemperies.domain.DayClassifier
import be.wallonie.intemperies.model.Criteria
import be.wallonie.intemperies.model.StationKind
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Contrôle manuel contre le service réel : LIVE_SPW=1 ./gradlew testDebugUnitTest --tests '*LiveSpwCheck*' */
class LiveSpwCheck {
    @Test
    fun wavreCurrentMonth() = runBlocking {
        assumeTrue(System.getenv("LIVE_SPW") != null)
        val zone = ZoneId.of("Europe/Brussels")
        val client = KiwisClient()
        val rainStations = client.fetchStations(StationKind.RAIN)
        val tempStations = client.fetchStations(StationKind.TEMPERATURE)
        println("Stations pluie actives: ${rainStations.size}, température: ${tempStations.size}")
        val wavre = rainStations.first { it.stationNo == "1043" }
        val today = LocalDate.now(zone)
        val from = today.withDayOfMonth(1).atStartOfDay(zone).toOffsetDateTime()
        val rain = client.fetchValues(wavre.tsId, from, today.plusDays(1).atStartOfDay(zone).toOffsetDateTime())
        println("Échantillons 5 min: ${rain.size}")
        val byDay = rain.groupBy { Instant.ofEpochSecond(it.epochSec).atZone(zone).toLocalDate() }
        for (d in 1..today.dayOfMonth) {
            val date = today.withDayOfMonth(d)
            val r = DayClassifier.classify(date, byDay[date].orEmpty(), emptyList(), Criteria(), zone, Instant.now(), temperatureAvailable = false)
            println("%s %-11s plage=%5.1f mm pluie=%.1f h consec=%.1f h 24h=%5.1f mm couv=%3.0f%% prov=%s".format(
                date, r.status, r.windowRainMm, r.rainHours, r.maxConsecutiveRainHours, r.dayRainMm, r.coverage * 100, r.provisional))
        }
    }
}
