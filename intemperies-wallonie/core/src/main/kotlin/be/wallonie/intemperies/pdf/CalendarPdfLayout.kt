package be.wallonie.intemperies.pdf

import be.wallonie.intemperies.model.DayReport
import be.wallonie.intemperies.model.DayStatus
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.ui.Fmt
import be.wallonie.intemperies.ui.legal.RegulatoryBasis

/** Style de texte (taille en points, couleur ARGB). */
data class PdfText(val size: Float, val bold: Boolean = false, val color: Int = INK) {
    companion object {
        const val INK = 0xFF212121.toInt()
    }
}

enum class PdfAlign { LEFT, CENTER, RIGHT }

/** Surface de dessin d'une page A4 (595 × 842 pt, origine en haut à gauche, y = ligne de base du texte). */
interface PdfCanvas {
    fun text(s: String, x: Float, y: Float, style: PdfText, align: PdfAlign = PdfAlign.LEFT)
    fun measure(s: String, style: PdfText): Float
    fun rect(left: Float, top: Float, right: Float, bottom: Float, fill: Int?, stroke: Int?, radius: Float = 0f, strokeWidth: Float = 0.6f)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float = 0.6f)
}

/** Document multi-pages (implémenté par android.graphics.pdf sur Android, PDFBox sous Windows). */
interface PdfWriter {
    fun newPage(): PdfCanvas
    fun finishPage()
}

/** Calendrier mensuel A4 : une page par commune (+ pages de suite si la liste des intempéries est longue). */
object CalendarPdfLayout {
    const val PAGE_W = 595f
    const val PAGE_H = 842f
    private const val MARGIN = 32f
    private const val CELL_H = 64f
    private const val ROW_H = 15f
    private const val FOOTER_H = 50f

    const val GREEN = 0xFF81C784.toInt()
    const val ORANGE = 0xFFFFB74D.toInt()
    const val RED = 0xFFE57373.toInt()
    const val GREY = 0xFFE0E0E0.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    private const val HEADER_BG = 0xFFE6EAF0.toInt()
    private const val ALT_BG = 0xFFF6F7FA.toInt()
    private const val BOX_BG = 0xFFF3F5F8.toInt()
    private const val STROKE = 0xFFA0A0A0.toInt()

    private val caption = PdfText(9f, true, 0xFF606C80.toInt())
    private val title = PdfText(18f, true)
    private val subtitle = PdfText(11f, true)
    private val body = PdfText(9f)
    private val bold = PdfText(9f, true)
    private val boldMuted = PdfText(9f, true, 0xFF6E6E6E.toInt())
    private val boldSmall = PdfText(7.5f, true)
    private val small = PdfText(7.5f)
    private val tiny = PdfText(6.5f)
    private val tinyBlue = PdfText(6.5f, true, 0xFF1565C0.toInt())

    fun statusColor(s: DayStatus): Int = when (s) {
        DayStatus.DRY -> GREEN
        DayStatus.WARNING -> ORANGE
        DayStatus.INTEMPERIE -> RED
        DayStatus.NO_DATA -> GREY
        DayStatus.IN_PROGRESS, DayStatus.FUTURE -> WHITE
    }

    fun render(doc: PdfWriter, reports: List<MonthReport>, generated: String) {
        require(reports.isNotEmpty())
        var pageNo = 0
        for (report in reports) {
            var c = doc.newPage().also { pageNo++ }
            var y = drawHeader(c, report)
            y = drawCalendar(c, report, y + 8f)
            y = drawLegend(c, y + 10f)
            y = drawSummary(c, report, y + 14f)
            val rows = report.days.filter { it.status == DayStatus.INTEMPERIE }
            var index = 0
            y = drawTableTitle(c, "Jours d'intempéries (${rows.size})", y + 14f)
            if (rows.isEmpty()) {
                c.text("Aucun jour d'intempérie sur la période.", MARGIN, y + 12f, body)
                y += 18f
            }
            while (index < rows.size) {
                y = drawTableHeader(c, y + 4f)
                while (index < rows.size && y + ROW_H < PAGE_H - FOOTER_H) {
                    y = drawRow(c, rows[index], y, index % 2 == 1)
                    index++
                }
                if (index < rows.size) {
                    drawFooter(c, pageNo, generated)
                    doc.finishPage()
                    c = doc.newPage().also { pageNo++ }
                    y = drawContinuation(c, report)
                }
            }
            val warnings = report.days.filter { it.status == DayStatus.WARNING }
            if (warnings.isNotEmpty() && y + 40f < PAGE_H - FOOTER_H) {
                y = drawTableTitle(c, "Jours de pluie sans intempérie (orange)", y + 16f)
                val text = warnings.joinToString(" · ") { "${it.date.dayOfMonth} (${Fmt.hours(it.rainHours)})" }
                drawWrapped(c, text, MARGIN, y + 12f, PAGE_W - 2 * MARGIN, small)
            }
            drawFooter(c, pageNo, generated)
            doc.finishPage()
        }
    }

    private fun drawHeader(c: PdfCanvas, r: MonthReport): Float {
        var y = MARGIN + 6f
        c.text("CALENDRIER DES INTEMPÉRIES", MARGIN, y, caption)
        y += 22f
        c.text("${r.commune.name} — ${Fmt.month(r.month)}", MARGIN, y, title)
        c.text("Province : ${r.commune.province} · INS ${r.commune.nis}", PAGE_W - MARGIN, y, small, PdfAlign.RIGHT)
        y += 16f
        val rain = r.rainStation?.let {
            "Pluviomètre : ${it.station.name} (SPW n° ${it.station.stationNo}, ${Fmt.num(it.distanceKm)} km${if (it.manual) ", choix manuel" else ""})"
        } ?: "Pluviomètre : aucun"
        val temp = r.temperatureStation?.let {
            "Température : ${it.station.name} (SPW n° ${it.station.stationNo}, ${Fmt.num(it.distanceKm)} km${if (it.manual) ", choix manuel" else ""})"
        } ?: "Température : aucune station à moins de ${Fmt.num(r.criteria.maxTemperatureDistanceKm, 0)} km"
        c.text(rain, MARGIN, y, body)
        y += 13f
        c.text(temp, MARGIN, y, body)
        y += 12f
        y = drawWrapped(c, Fmt.freshness(r), MARGIN, y, PAGE_W - 2 * MARGIN, small)
        y += 6f
        // Encadré dimensionné d'après le texte (aucun débordement quels que soient les paramètres).
        val w = PAGE_W - 2 * MARGIN - 12f
        val criteria = "Critères : " + Fmt.criteriaSummary(r.criteria)
        val basis = "Base réglementaire : ${RegulatoryBasis.SHORT_REF} — jour où le travail a été rendu impossible pendant " +
            "quatre heures au moins par suite de conditions météorologiques défavorables, sur reconnaissance de l'adjudicateur."
        val h1 = wrap(c, criteria, w, small).size * (small.size + 2f)
        val h2 = wrap(c, basis, w, tiny).size * (tiny.size + 2f)
        val boxBottom = y + 6f + h1 + 2f + h2 + 4f
        c.rect(MARGIN, y, PAGE_W - MARGIN, boxBottom, fill = BOX_BG, stroke = null, radius = 4f)
        val yy = drawWrapped(c, criteria, MARGIN + 6f, y + 12f, w, small)
        drawWrapped(c, basis, MARGIN + 6f, yy + 10f, w, tiny)
        return boxBottom
    }

    private fun drawCalendar(c: PdfCanvas, r: MonthReport, top: Float): Float {
        val colW = (PAGE_W - 2 * MARGIN) / 7f
        listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim").forEachIndexed { i, d ->
            c.text(d, MARGIN + i * colW + colW / 2, top + 10f, bold, PdfAlign.CENTER)
        }
        val gridTop = top + 16f
        val offset = r.month.atDay(1).dayOfWeek.value - 1
        val rowsCount = (offset + r.month.lengthOfMonth() + 6) / 7
        for (day in r.days) {
            val pos = offset + day.date.dayOfMonth - 1
            val col = pos % 7
            val row = pos / 7
            drawCell(
                c, day,
                MARGIN + col * colW + 1.5f, gridTop + row * CELL_H + 1.5f,
                MARGIN + (col + 1) * colW - 1.5f, gridTop + (row + 1) * CELL_H - 1.5f,
            )
        }
        return gridTop + rowsCount * CELL_H
    }

    private fun drawCell(c: PdfCanvas, d: DayReport, l: Float, t: Float, r: Float, b: Float) {
        c.rect(l, t, r, b, fill = statusColor(d.status), stroke = STROKE, radius = 4f)
        val nonWorking = d.isWeekend || d.holidayName != null
        c.text(d.date.dayOfMonth.toString(), l + 4f, t + 11f, if (nonWorking) boldMuted else bold)
        if (d.holidayName != null) c.text("Férié", r - 4f, t + 10f, tiny, PdfAlign.RIGHT)
        if (d.status == DayStatus.FUTURE) return
        var y = t + 23f
        if (d.status != DayStatus.NO_DATA || d.windowRainMm > 0) {
            c.text("Pluie ${Fmt.hours(d.rainHours)}", l + 4f, y, tiny); y += 9f
            c.text("Consec. ${Fmt.hours(d.maxConsecutiveRainHours)}", l + 4f, y, tiny); y += 9f
            c.text(Fmt.mm(d.windowRainMm), l + 4f, y, tiny); y += 9f
        } else {
            c.text("Sans données", l + 4f, y, tiny); y += 9f
        }
        d.meanTemperatureC?.let {
            val flag = when { d.coldReached == true -> " gel"; d.heatReached == true -> " chaud"; else -> "" }
            c.text("T ${Fmt.temp(it)}$flag", l + 4f, y, if (flag.isNotEmpty()) tinyBlue else tiny)
        }
        if (d.provisional) c.text("*", r - 5f, b - 4f, bold, PdfAlign.RIGHT)
    }

    private fun drawLegend(c: PdfCanvas, top: Float): Float {
        val items = listOf(
            DayStatus.DRY to "Pas d'intempérie",
            DayStatus.WARNING to "Pluie sans intempérie",
            DayStatus.INTEMPERIE to "Intempérie",
            DayStatus.NO_DATA to "Données insuffisantes",
        )
        var x = MARGIN
        for ((s, label) in items) {
            c.rect(x, top, x + 10f, top + 10f, fill = statusColor(s), stroke = STROKE)
            c.text(label, x + 14f, top + 8.5f, small)
            x += 22f + c.measure(label, small)
        }
        c.text("gel / chaud : seuil de température atteint · * : précipitations non validées par le SPW", MARGIN, top + 20f, small)
        return top + 22f
    }

    private fun drawSummary(c: PdfCanvas, r: MonthReport, top: Float): Float {
        val red = r.days.filter { it.status == DayStatus.INTEMPERIE }
        val redWorking = red.count { !it.isWeekend && it.holidayName == null }
        c.text(
            "Intempéries : ${red.size} jour(s) dont $redWorking ouvrable(s) · Orange : ${r.count(DayStatus.WARNING)} · " +
                "Vert : ${r.count(DayStatus.DRY)} · Sans données : ${r.count(DayStatus.NO_DATA)}",
            MARGIN, top, bold,
        )
        return top
    }

    private fun drawTableTitle(c: PdfCanvas, text: String, top: Float): Float {
        c.text(text, MARGIN, top, subtitle)
        return top + 4f
    }

    private val cols = floatArrayOf(0f, 62f, 92f, 150f, 210f, 280f, 340f, 392f)

    private fun drawTableHeader(c: PdfCanvas, top: Float): Float {
        val headers = listOf("Date", "Jour", "Pluie plage", "Durée pluie", "Consécutive max", "Pluie 24 h", "T moy.", "Motif")
        c.rect(MARGIN, top, PAGE_W - MARGIN, top + ROW_H, fill = HEADER_BG, stroke = null)
        headers.forEachIndexed { i, h -> c.text(h, MARGIN + cols[i] + 3f, top + 11f, boldSmall) }
        return top + ROW_H
    }

    private fun drawRow(c: PdfCanvas, d: DayReport, top: Float, alt: Boolean): Float {
        if (alt) c.rect(MARGIN, top, PAGE_W - MARGIN, top + ROW_H, fill = ALT_BG, stroke = null)
        val values = listOf(
            Fmt.date(d.date) + if (d.provisional) "*" else "",
            Fmt.dow(d.date),
            Fmt.mm(d.windowRainMm),
            Fmt.hours(d.rainHours),
            Fmt.hours(d.maxConsecutiveRainHours),
            Fmt.mm(d.dayRainMm),
            Fmt.temp(d.meanTemperatureC),
            d.reasons.joinToString(" + ") { Fmt.reasonShort(it) },
        )
        values.forEachIndexed { i, v -> c.text(v, MARGIN + cols[i] + 3f, top + 11f, small) }
        return top + ROW_H
    }

    private fun drawContinuation(c: PdfCanvas, r: MonthReport): Float {
        c.text("${r.commune.name} — ${Fmt.month(r.month)} (suite)", MARGIN, MARGIN + 10f, subtitle)
        return MARGIN + 16f
    }

    private fun drawFooter(c: PdfCanvas, page: Int, generated: String) {
        val y = PAGE_H - MARGIN + 4f
        c.line(MARGIN, y - 22f, PAGE_W - MARGIN, y - 22f, STROKE)
        c.text(
            "Sources des données : SPW — hydrometrie.wallonie.be (réseau hydrométrique wallon). Données indicatives, sans valeur légale.",
            MARGIN, y - 12f, tiny,
        )
        c.text(
            "* : contient des données non encore validées par le SPW (code qualité ≥ 200). Généré le $generated — Intempéries Wallonie.",
            MARGIN, y - 3f, tiny,
        )
        c.text("p. $page", PAGE_W - MARGIN, y - 3f, tiny, PdfAlign.RIGHT)
    }

    private fun wrap(c: PdfCanvas, text: String, width: Float, style: PdfText): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (c.measure(candidate, style) > width && line.isNotEmpty()) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    /** Texte renvoyé à la ligne ; renvoie l'ordonnée de la dernière ligne. */
    private fun drawWrapped(c: PdfCanvas, text: String, x: Float, top: Float, width: Float, style: PdfText): Float {
        var y = top
        wrap(c, text, width, style).forEachIndexed { i, l ->
            if (i > 0) y += style.size + 2f
            c.text(l, x, y, style)
        }
        return y
    }
}
