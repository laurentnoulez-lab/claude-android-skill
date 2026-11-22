package be.wallonie.intemperies.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import be.wallonie.intemperies.export.CalendarExporter
import be.wallonie.intemperies.export.ExportFormat
import be.wallonie.intemperies.export.ExportNames
import be.wallonie.intemperies.model.MonthReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Rendu Android (android.graphics.pdf) de la mise en page commune [CalendarPdfLayout]. */
class CalendarPdfExporter(private val context: Context) {

    suspend fun export(reports: List<MonthReport>): File = withContext(Dispatchers.Default) {
        val doc = PdfDocument()
        val writer = AndroidPdfWriter(doc)
        CalendarPdfLayout.render(writer, reports, LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")))
        val file = File(CalendarExporter.exportDir(context), ExportNames.fileName(reports, ExportFormat.PDF))
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        file
    }
}

private class AndroidPdfWriter(private val doc: PdfDocument) : PdfWriter {
    private var page: PdfDocument.Page? = null
    private var number = 0

    override fun newPage(): PdfCanvas {
        val p = doc.startPage(PdfDocument.PageInfo.Builder(CalendarPdfLayout.PAGE_W.toInt(), CalendarPdfLayout.PAGE_H.toInt(), ++number).create())
        page = p
        return AndroidPdfCanvas(p.canvas)
    }

    override fun finishPage() {
        page?.let { doc.finishPage(it) }
        page = null
    }
}

private class AndroidPdfCanvas(private val c: Canvas) : PdfCanvas {
    private val paints = HashMap<Pair<PdfText, PdfAlign>, Paint>()

    private fun paint(style: PdfText, align: PdfAlign) = paints.getOrPut(style to align) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = style.size
            color = style.color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
            textAlign = when (align) {
                PdfAlign.LEFT -> Paint.Align.LEFT
                PdfAlign.CENTER -> Paint.Align.CENTER
                PdfAlign.RIGHT -> Paint.Align.RIGHT
            }
        }
    }

    override fun text(s: String, x: Float, y: Float, style: PdfText, align: PdfAlign) = c.drawText(s, x, y, paint(style, align))

    override fun measure(s: String, style: PdfText): Float = paint(style, PdfAlign.LEFT).measureText(s)

    override fun rect(left: Float, top: Float, right: Float, bottom: Float, fill: Int?, stroke: Int?, radius: Float, strokeWidth: Float) {
        val r = RectF(left, top, right, bottom)
        fill?.let {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = it }
            if (radius > 0f) c.drawRoundRect(r, radius, radius, p) else c.drawRect(r, p)
        }
        stroke?.let {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = it; this.strokeWidth = strokeWidth }
            if (radius > 0f) c.drawRoundRect(r, radius, radius, p) else c.drawRect(r, p)
        }
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) =
        c.drawLine(x1, y1, x2, y2, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = width })
}
