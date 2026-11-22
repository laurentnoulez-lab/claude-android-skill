package be.wallonie.intemperies.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import be.wallonie.intemperies.data.BRUSSELS
import be.wallonie.intemperies.data.SyncOutcome
import be.wallonie.intemperies.export.CalendarWorkbook
import be.wallonie.intemperies.ui.theme.IntemperiesTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.YearMonth

/**
 * Autodiagnostic : « IntemperiesWallonie.exe --selfcheck <dossier> » (ou java -jar …) vérifie la connexion au SPW,
 * la synchronisation, les exports PDF / Excel et le rendu graphique, puis écrit un rapport dans le dossier.
 */
object SelfCheck {
    fun run(out: File): Int {
        out.mkdirs()
        val log = StringBuilder()
        fun step(name: String, block: () -> String): Boolean = runCatching { block() }
            .fold({ log.appendLine("OK   $name : $it"); true }, { log.appendLine("ÉCHEC $name : ${it.javaClass.simpleName} ${it.message}"); false })
        System.setProperty("intemperies.home", File(out, "data").path)
        val repo = DesktopRepository()
        val ok = listOf(
            step("Java") { "${System.getProperty("java.version")} (${System.getProperty("os.name")})" },
            step("Synchronisation SPW (Wavre)") {
                repo.toggleCommune("25112")
                when (val r = runBlocking { repo.sync() }) {
                    is SyncOutcome.Success -> "${r.stations} station(s), ${r.samples} mesures"
                    is SyncOutcome.Failure -> error(r.message)
                }
            },
            step("Exports PDF et Excel") {
                val rep = runBlocking { repo.monthReport("25112", YearMonth.now(BRUSSELS)) } ?: error("rapport vide")
                DesktopPdf.export(listOf(rep), File(out, "selfcheck.pdf"))
                File(out, "selfcheck.xlsx").writeBytes(CalendarWorkbook.build(listOf(rep), "autodiagnostic"))
                "${rep.count(be.wallonie.intemperies.model.DayStatus.INTEMPERIE)} intempérie(s) ce mois"
            },
            step("Rendu graphique") {
                val scene = ImageComposeScene(1200, 800, Density(1f)) { IntemperiesTheme { DesktopApp(repo, null) } }
                repeat(10) { scene.render(System.nanoTime()); Thread.sleep(100) }
                File(out, "selfcheck.png").writeBytes(scene.render().encodeToData()!!.bytes)
                scene.close()
                "capture écrite"
            },
        ).all { it }
        File(out, "selfcheck.txt").writeText(log.toString())
        print(log)
        return if (ok) 0 else 1
    }
}
