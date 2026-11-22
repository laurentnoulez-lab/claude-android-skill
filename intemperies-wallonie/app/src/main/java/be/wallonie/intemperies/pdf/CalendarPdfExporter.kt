package be.wallonie.intemperies.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import be.wallonie.intemperies.model.DayReport
import be.wallonie.intemperies.model.DayStatus
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.ui.Fmt
import be.wallonie.intemperies.ui.legal.RegulatoryBasis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Calendrier mensuel A4 (une page par commune, + pages de suite si la liste est longue). */
class CalendarPdfExporter(private val context: Context) {

    suspend fun export(reports: List<MonthReport>): File = withContext(Dispatchers.Default) {
        require(reports.isNotEmpty())
        val doc = PdfDocument()
        var pageNo = 0
        val generated = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        for (report in reports) {
            var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, ++pageNo).create())
            var y = drawHeader(page.canvas, report)
            y = drawCalendar(page.canvas, report, y + 8f)
            y = drawLegend(page.canvas, y + 10f)
            y = drawSummary(page.canvas, report, y + 14f)
            val rows = report.days.filter { it.status == DayStatus.INTEMPERIE }
            var index = 0
            y = drawTableTitle(page.canvas, "Jours d'intempéries (${rows.size})", y + 14f)
            if (rows.isEmpty()) {
                page.canvas.drawText("Aucun jour d'intempérie sur la période.", MARGIN, y + 12f, body)
                y += 18f
            }
            while (index < rows.size) {
                y = drawTableHeader(page.canvas, y + 4f)
                while (index < rows.size && y + ROW_H < PAGE_H - FOOTER_H) {
                    y = drawRow(page.canvas, rows[index], report, y, index % 2 == 1)
                    index++
                }
                if (index < rows.size) {
                    drawFooter(page.canvas, pageNo, generated)
                    doc.finishPage(page)
                    page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, ++pageNo).create())
                    y = drawContinuation(page.canvas, report)
                }
            }
            val warnings = report.days.filter { it.status == DayStatus.WARNING }
            if (warnings.isNotEmpty() && y + 40f < PAGE_H - FOOTER_H) {
                y = drawTableTitle(page.canvas, "Jours de pluie sans intempérie (orange)", y + 16f)
                val text = warnings.joinToString(" · ") { "${it.date.dayOfMonth} (${Fmt.hours(it.rainHours)})" }
                y = drawWrapped(page.canvas, text, MARGIN, y + 12f, PAGE_W - 2 * MARGIN, small)
            }
            drawFooter(page.canvas, pageNo, generated)
            doc.finishPage(page)
        }
        val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
        val first = reports.first()
        val name = if (reports.size == 1) {
            "Intemperies_${safe(first.commune.name)}_${first.month}.pdf"
        } else {
            "Intemperies_${reports.size}_communes_${first.month}.pdf"
        }
        val file = File(dir, name)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        file
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9-]+"), "_")

    // ---------------------------------------------------------------------------------------------

    private fun drawHeader(c: Canvas, r: MonthReport): Float {
        var y = MARGIN + 6f
        c.drawText("CALENDRIER DES INTEMPÉRIES", MARGIN, y, caption)
        y += 22f
        c.drawText("${r.commune.name} — ${Fmt.month(r.month)}", MARGIN, y, title)
        c.drawText("Province : ${r.commune.province} · INS ${r.commune.nis}", PAGE_W - MARGIN, y, small.alignRight())
        y += 16f
        val rain = r.rainStation?.let {
            "Pluviomètre : ${it.station.name} (SPW n° ${it.station.stationNo}, ${Fmt.num(it.distanceKm)} km${if (it.manual) ", choix manuel" else ""})"
        } ?: "Pluviomètre : aucun"
        val temp = r.temperatureStation?.let {
            "Température : ${it.station.name} (SPW n° ${it.station.stationNo}, ${Fmt.num(it.distanceKm)} km${if (it.manual) ", choix manuel" else ""})"
        } ?: "Température : aucune station à moins de ${Fmt.num(r.criteria.maxTemperatureDistanceKm, 0)} km"
        c.drawText(rain, MARGIN, y, body)
        y += 13f
        c.drawText(temp, MARGIN, y, body)
        y += 6f
        val box = RectF(MARGIN, y, PAGE_W - MARGIN, y + 52f)
        c.drawRoundRect(box, 4f, 4f, boxFill)
        val yy = drawWrapped(c, "Critères : " + Fmt.criteriaSummary(r.criteria), MARGIN + 6f, y + 12f, box.width() - 12f, small)
        drawWrapped(
            c,
            "Base réglementaire : ${RegulatoryBasis.SHORT_REF} — jour où le travail a été rendu impossible pendant " +
                "quatre heures au moins par suite de conditions météorologiques défavorables, sur reconnaissance de l'adjudicateur.",
            MARGIN + 6f, yy + 10f, box.width() - 12f, tiny,
        )
        return box.bottom
    }

    private fun drawCalendar(c: Canvas, r: MonthReport, top: Float): Float {
        val colW = (PAGE_W - 2 * MARGIN) / 7f
        val days = listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")
        days.forEachIndexed { i, d ->
            c.drawText(d, MARGIN + i * colW + colW / 2, top + 10f, bold.center())
        }
        val gridTop = top + 16f
        val offset = r.month.atDay(1).dayOfWeek.value - 1
        val rowsCount = (offset + r.month.lengthOfMonth() + 6) / 7
        for (day in r.days) {
            val pos = offset + day.date.dayOfMonth - 1
            val col = pos % 7
            val row = pos / 7
            val rect = RectF(
                MARGIN + col * colW + 1.5f, gridTop + row * CELL_H + 1.5f,
                MARGIN + (col + 1) * colW - 1.5f, gridTop + (row + 1) * CELL_H - 1.5f,
            )
            drawCell(c, rect, day)
        }
        return gridTop + rowsCount * CELL_H
    }

    private fun drawCell(c: Canvas, rect: RectF, d: DayReport) {
        fill.color = statusColor(d.status)
        c.drawRoundRect(rect, 4f, 4f, fill)
        c.drawRoundRect(rect, 4f, 4f, stroke)
        val nonWorking = d.isWeekend || d.holidayName != null
        val dayPaint = if (nonWorking) boldMuted else bold
        c.drawText(d.date.dayOfMonth.toString(), rect.left + 4f, rect.top + 11f, dayPaint)
        if (d.holidayName != null) c.drawText("Férié", rect.right - 4f, rect.top + 10f, tiny.alignRight())
        if (d.status == DayStatus.FUTURE) return
        var y = rect.top + 23f
        if (d.status != DayStatus.NO_DATA || d.windowRainMm > 0) {
            c.drawText("Pluie ${Fmt.hours(d.rainHours)}", rect.left + 4f, y, tiny); y += 9f
            c.drawText("Consec. ${Fmt.hours(d.maxConsecutiveRainHours)}", rect.left + 4f, y, tiny); y += 9f
            c.drawText(Fmt.mm(d.windowRainMm), rect.left + 4f, y, tiny); y += 9f
        } else {
            c.drawText("Sans données", rect.left + 4f, y, tiny); y += 9f
        }
        d.meanTemperatureC?.let {
            c.drawText("T ${Fmt.temp(it)}" + if (d.belowFrostThreshold == true) " gel" else "", rect.left + 4f, y, if (d.belowFrostThreshold == true) tinyBlue else tiny)
        }
        if (d.provisional) c.drawText("*", rect.right - 5f, rect.bottom - 4f, bold.alignRight())
    }

    private fun drawLegend(c: Canvas, top: Float): Float {
        val items = listOf(
            DayStatus.DRY to "Pas d'intempérie",
            DayStatus.WARNING to "Pluie sans intempérie",
            DayStatus.INTEMPERIE to "Intempérie",
            DayStatus.NO_DATA to "Données insuffisantes",
        )
        var x = MARGIN
        for ((s, label) in items) {
            fill.color = statusColor(s)
            val r = RectF(x, top, x + 10f, top + 10f)
            c.drawRect(r, fill); c.drawRect(r, stroke)
            c.drawText(label, x + 14f, top + 8.5f, small)
            x += 22f + small.measureText(label)
        }
        c.drawText("gel : T moy. sous le seuil · * : données non validées", PAGE_W - MARGIN, top + 8.5f, small.alignRight())
        return top + 10f
    }

    private fun drawSummary(c: Canvas, r: MonthReport, top: Float): Float {
        val red = r.days.filter { it.status == DayStatus.INTEMPERIE }
        val redWorking = red.count { !it.isWeekend && it.holidayName == null }
        val txt = "Intempéries : ${red.size} jour(s) dont $redWorking ouvrable(s) · Orange : ${r.count(DayStatus.WARNING)} · " +
            "Vert : ${r.count(DayStatus.DRY)} · Sans données : ${r.count(DayStatus.NO_DATA)}"
        c.drawText(txt, MARGIN, top, bold)
        return top
    }

    private fun drawTableTitle(c: Canvas, text: String, top: Float): Float {
        c.drawText(text, MARGIN, top, subtitle)
        return top + 4f
    }

    private val cols = floatArrayOf(0f, 62f, 92f, 150f, 210f, 280f, 340f, 392f)

    private fun drawTableHeader(c: Canvas, top: Float): Float {
        val headers = listOf("Date", "Jour", "Pluie plage", "Durée pluie", "Consécutive max", "Pluie 24 h", "T moy.", "Motif")
        fill.color = HEADER_BG
        c.drawRect(MARGIN, top, PAGE_W - MARGIN, top + ROW_H, fill)
        headers.forEachIndexed { i, h -> c.drawText(h, MARGIN + cols[i] + 3f, top + 11f, boldSmall) }
        return top + ROW_H
    }

    private fun drawRow(c: Canvas, d: DayReport, r: MonthReport, top: Float, alt: Boolean): Float {
        if (alt) {
            fill.color = ALT_BG
            c.drawRect(MARGIN, top, PAGE_W - MARGIN, top + ROW_H, fill)
        }
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
        values.forEachIndexed { i, v -> c.drawText(v, MARGIN + cols[i] + 3f, top + 11f, small) }
        return top + ROW_H
    }

    private fun drawContinuation(c: Canvas, r: MonthReport): Float {
        c.drawText("${r.commune.name} — ${Fmt.month(r.month)} (suite)", MARGIN, MARGIN + 10f, subtitle)
        return MARGIN + 16f
    }

    private fun drawFooter(c: Canvas, page: Int, generated: String) {
        val y = PAGE_H - MARGIN + 4f
        c.drawLine(MARGIN, y - 22f, PAGE_W - MARGIN, y - 22f, stroke)
        c.drawText(
            "Sources des données : SPW — hydrometrie.wallonie.be (réseau hydrométrique wallon). Données indicatives, sans valeur légale.",
            MARGIN, y - 12f, tiny,
        )
        c.drawText(
            "* : contient des données non encore validées par le SPW (code qualité ≥ 200). Généré le $generated — Intempéries Wallonie.",
            MARGIN, y - 3f, tiny,
        )
        c.drawText("p. $page", PAGE_W - MARGIN, y - 3f, tiny.alignRight())
    }

    private fun drawWrapped(c: Canvas, text: String, x: Float, top: Float, width: Float, paint: Paint): Float {
        var y = top
        var line = StringBuilder()
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > width && line.isNotEmpty()) {
                c.drawText(line.toString(), x, y, paint)
                y += paint.textSize + 2f
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) c.drawText(line.toString(), x, y, paint)
        return y
    }

    companion object {
        private const val PAGE_W = 595
        private const val PAGE_H = 842
        private const val MARGIN = 32f
        private const val CELL_H = 64f
        private const val ROW_H = 15f
        private const val FOOTER_H = 50f

        val GREEN = Color.rgb(129, 199, 132)
        val ORANGE = Color.rgb(255, 183, 77)
        val RED = Color.rgb(229, 115, 115)
        val GREY = Color.rgb(224, 224, 224)
        val WHITE = Color.WHITE
        private val HEADER_BG = Color.rgb(230, 234, 240)
        private val ALT_BG = Color.rgb(246, 247, 250)

        fun statusColor(s: DayStatus): Int = when (s) {
            DayStatus.DRY -> GREEN
            DayStatus.WARNING -> ORANGE
            DayStatus.INTEMPERIE -> RED
            DayStatus.NO_DATA -> GREY
            DayStatus.IN_PROGRESS, DayStatus.FUTURE -> WHITE
        }

        private fun paint(size: Float, bold: Boolean = false, color: Int = Color.rgb(33, 33, 33)) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }

        private val caption = paint(9f, true, Color.rgb(96, 108, 128))
        private val title = paint(18f, true)
        private val subtitle = paint(11f, true)
        private val body = paint(9f)
        private val bold = paint(9f, true)
        private val boldMuted = paint(9f, true, Color.rgb(110, 110, 110))
        private val boldSmall = paint(7.5f, true)
        private val small = paint(7.5f)
        private val tiny = paint(6.5f)
        private val tinyBlue = paint(6.5f, true, Color.rgb(21, 101, 192))
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(243, 245, 248) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 0.6f; color = Color.rgb(160, 160, 160)
        }

        private fun Paint.alignRight() = Paint(this).apply { textAlign = Paint.Align.RIGHT }
        private fun Paint.center() = Paint(this).apply { textAlign = Paint.Align.CENTER }
    }
}
