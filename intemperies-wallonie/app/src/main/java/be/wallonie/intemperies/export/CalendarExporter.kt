package be.wallonie.intemperies.export

import android.content.Context
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.pdf.CalendarPdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class ExportFormat(val label: String, val mime: String, val extension: String) {
    PDF("Calendrier PDF", "application/pdf", "pdf"),
    XLSX("Classeur Excel", XlsxWriter.MIME, "xlsx"),
}

data class ExportedFile(val file: File, val format: ExportFormat)

/** Point d'entrée unique des exports (fichiers écrits dans le cache, partagés via FileProvider). */
class CalendarExporter(private val context: Context, private val pdf: CalendarPdfExporter) {

    suspend fun export(reports: List<MonthReport>, format: ExportFormat): ExportedFile {
        require(reports.isNotEmpty())
        val file = when (format) {
            ExportFormat.PDF -> pdf.export(reports)
            ExportFormat.XLSX -> withContext(Dispatchers.Default) {
                val bytes = CalendarWorkbook.build(reports, LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")))
                File(exportDir(context), fileName(reports, format)).apply { writeBytes(bytes) }
            }
        }
        return ExportedFile(file, format)
    }

    companion object {
        fun exportDir(context: Context): File = File(context.cacheDir, "exports").apply {
            mkdirs()
            listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
        }

        fun fileName(reports: List<MonthReport>, format: ExportFormat): String {
            val first = reports.first()
            val base = if (reports.size == 1) {
                "Intemperies_${first.commune.name.replace(Regex("[^A-Za-z0-9-]+"), "_")}_${first.month}"
            } else {
                "Intemperies_${reports.size}_communes_${first.month}"
            }
            return "$base.${format.extension}"
        }
    }
}
