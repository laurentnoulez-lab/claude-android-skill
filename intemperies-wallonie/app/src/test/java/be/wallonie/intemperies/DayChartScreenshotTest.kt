package be.wallonie.intemperies

import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import be.wallonie.intemperies.data.remote.KiwisClient
import be.wallonie.intemperies.domain.DayClassifier
import be.wallonie.intemperies.domain.StationMatcher
import be.wallonie.intemperies.model.Commune
import be.wallonie.intemperies.model.Criteria
import be.wallonie.intemperies.model.DayReport
import be.wallonie.intemperies.model.StationKind
import be.wallonie.intemperies.ui.calendar.DayChart
import be.wallonie.intemperies.ui.theme.IntemperiesTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Capture du graphique journalier avec des données SPW réelles.
 * SCREENSHOT_OUT=/dossier ./gradlew testDebugUnitTest -Pscreenshots --tests '*DayChartScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xxhdpi", application = android.app.Application::class)
class DayChartScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val out: String? = System.getenv("SCREENSHOT_OUT")

    private fun realDay(date: LocalDate, criteria: Criteria): Pair<DayReport, String?> = runBlocking {
        val zone = ZoneId.of("Europe/Brussels")
        val client = KiwisClient()
        val commune = Commune("82003", "Bastogne", "Luxembourg", 50.0157, 5.76157)
        val rainSt = StationMatcher.assign(commune, client.fetchStations(StationKind.RAIN), null)!!
        val tempSt = StationMatcher.assign(commune, client.fetchStations(StationKind.TEMPERATURE), null, 40.0)
        val from = date.atStartOfDay(zone).toOffsetDateTime()
        val to = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
        val rain = client.fetchValues(rainSt.station.tsId, from, to)
        val temp = tempSt?.let { client.fetchValues(it.station.tsId, from, to) }.orEmpty()
        DayClassifier.classify(date, rain, temp, criteria, zone, Instant.now(), tempSt != null) to
            tempSt?.let { "${it.station.name}, ${"%.1f".format(it.distanceKm)} km" }
    }

    private fun shoot(name: String, date: LocalDate, night: Boolean, tapFraction: Float?, criteria: Criteria = Criteria()) {
        assumeTrue(out != null)
        if (night) RuntimeEnvironment.setQualifiers("+night")
        val (day, label) = realDay(date, criteria)
        compose.setContent {
            IntemperiesTheme {
                Surface(Modifier.width(400.dp)) {
                    DayChart(day, criteria, label, Modifier.padding(16.dp))
                }
            }
        }
        if (tapFraction != null) {
            compose.onRoot().performTouchInput { click(Offset(width * tapFraction, height * 0.35f)) }
        }
        compose.waitForIdle()
        // Rendu logiciel de la vue (captureToImage n'est pas supporté sous Robolectric).
        val view = compose.activity.findViewById<View>(android.R.id.content)
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bmp)) }
        File(out!!).mkdirs()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("Capture : $name (${day.date}, ${day.status}, ${day.windowRainMm} mm, T moy ${day.meanTemperatureC})")
    }

    @Test fun light() = shoot("chart_light", LocalDate.of(2026, 11, 17), night = false, tapFraction = null)

    @Test fun darkWithSelection() = shoot("chart_dark_selected", LocalDate.of(2026, 11, 17), night = true, tapFraction = 0.55f)

    @Test fun customWindow() = shoot(
        "chart_window_06_18", LocalDate.of(2026, 11, 17), night = false, tapFraction = null,
        criteria = Criteria(windowStart = java.time.LocalTime.of(6, 0), windowEnd = java.time.LocalTime.of(18, 0), stepMinutes = 60),
    )

    @Test fun coldDay() = shoot("chart_cold", LocalDate.of(2026, 11, 21), night = false, tapFraction = 0.3f)
}
