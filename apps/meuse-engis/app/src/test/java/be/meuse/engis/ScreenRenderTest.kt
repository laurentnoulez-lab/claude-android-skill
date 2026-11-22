package be.meuse.engis

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.core.view.drawToBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import be.meuse.engis.data.KiwisClient
import be.meuse.engis.domain.SnapshotBuilder
import be.meuse.engis.ui.MeuseScreen
import be.meuse.engis.ui.MeuseTheme
import be.meuse.engis.ui.MeuseUiState
import be.meuse.engis.ui.Reference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Rendu de l'écran principal avec une réponse KiWIS réelle (22/11/2026) pour contrôle visuel.
 * Les images sont écrites dans app/build/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w392dp-h1200dp-xxhdpi")
class ScreenRenderTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun snapshot() = SnapshotBuilder.build(
        series = KiwisClient.parse(javaClass.getResource("/kiwis_2026-11-22.json")!!.readText()),
        today = LocalDate.of(2026, 11, 22),
        zone = ZoneId.of("Europe/Brussels"),
        fetchedAt = Instant.parse("2026-11-22T07:45:00Z"),
    )

    private fun render(name: String, reference: Reference) {
        compose.setContent {
            MeuseTheme {
                MeuseScreen(
                    state = MeuseUiState(snapshot = snapshot(), reference = reference),
                    onRefresh = {},
                    onReference = {},
                )
            }
        }
        compose.waitForIdle()
        val bmp = compose.activity.window.decorView.drawToBitmap()
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun normale() = render("ecran_normale", Reference.NORMALE)

    @Test fun moisEnCours() = render("ecran_mois_en_cours", Reference.MOIS_EN_COURS)

    @Test @Config(qualifiers = "w392dp-h1200dp-night-xxhdpi")
    fun sombre() = render("ecran_sombre", Reference.NORMALE)
}
