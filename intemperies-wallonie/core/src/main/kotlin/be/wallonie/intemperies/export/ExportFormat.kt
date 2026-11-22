package be.wallonie.intemperies.export

import be.wallonie.intemperies.model.MonthReport

enum class ExportFormat(val label: String, val mime: String, val extension: String) {
    PDF("Calendrier PDF", "application/pdf", "pdf"),
    XLSX("Classeur Excel", XlsxWriter.MIME, "xlsx"),
}

object ExportNames {
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
