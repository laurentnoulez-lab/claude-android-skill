package be.wallonie.intemperies.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import be.wallonie.intemperies.data.BRUSSELS
import be.wallonie.intemperies.export.CalendarWorkbook
import be.wallonie.intemperies.ui.theme.IntemperiesTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.YearMonth

/** SCREENSHOT_OUT=/dossier ./gradlew :desktop:test -Pscreenshots — rendu hors écran avec données SPW réelles. */
class DesktopScreenshotTest {
    @Test
    fun mainWindowPdfAndExcel() {
        val out = System.getenv("SCREENSHOT_OUT")
        assumeTrue(out != null)
        File(out!!).mkdirs()
        System.setProperty("intemperies.home", Files.createTempDirectory("intemperies").toFile().path)
        val repo = DesktopRepository()
        repo.toggleCommune("82003")
        repo.toggleCommune("25112")
        repo.toggleCommune("54007") // Mouscron : aucune station de température dans le rayon par défaut
        runBlocking { repo.sync() }

        val scene = ImageComposeScene(1360, 880, Density(1f)) { IntemperiesTheme { DesktopApp(repo, null) } }
        fun settle() = repeat(15) { scene.render(System.nanoTime()); Thread.sleep(120) }
        settle()
        File(out, "desktop_main.png").writeBytes(scene.render().encodeToData()!!.bytes)
        // Clic sur un jour du calendrier (4e ligne, 2e colonne) pour ouvrir le détail.
        val p = Offset(340f + 16f + 720f / 7 * 1.5f, 200f + 3.5f * (720f / 7 / 1.45f))
        scene.sendPointerEvent(PointerEventType.Press, p)
        scene.sendPointerEvent(PointerEventType.Release, p)
        settle()
        File(out, "desktop_day.png").writeBytes(scene.render().encodeToData()!!.bytes)
        scene.close()

        val month = YearMonth.now(BRUSSELS)
        val reports = runBlocking { listOfNotNull(repo.monthReport("82003", month), repo.monthReport("25112", month)) }
        DesktopPdf.export(reports, File(out, "desktop.pdf"))
        File(out, "desktop.xlsx").writeBytes(CalendarWorkbook.build(reports, "test"))
        println("Stockage : ${AppPaths.root} -> ${AppPaths.root.walk().filter { it.isFile }.map { it.name + " " + it.length() }.toList()}")
    }
}
