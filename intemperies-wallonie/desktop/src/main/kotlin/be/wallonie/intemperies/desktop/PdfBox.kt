package be.wallonie.intemperies.desktop

import be.wallonie.intemperies.pdf.CalendarPdfLayout
import be.wallonie.intemperies.pdf.PdfAlign
import be.wallonie.intemperies.pdf.PdfCanvas
import be.wallonie.intemperies.pdf.PdfText
import be.wallonie.intemperies.pdf.PdfWriter
import be.wallonie.intemperies.model.MonthReport
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.awt.Color
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Rendu Windows (PDFBox) de la mise en page commune [CalendarPdfLayout] — polices DejaVu embarquées. */
object DesktopPdf {
    fun export(reports: List<MonthReport>, file: File) {
        PDDocument().use { doc ->
            val regular = PDType0Font.load(doc, DesktopPdf::class.java.getResourceAsStream("/fonts/DejaVuSans.ttf"))
            val bold = PDType0Font.load(doc, DesktopPdf::class.java.getResourceAsStream("/fonts/DejaVuSans-Bold.ttf"))
            val writer = BoxWriter(doc, regular, bold)
            CalendarPdfLayout.render(writer, reports, LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")))
            writer.finishPage()
            doc.save(file)
        }
    }

    private class BoxWriter(val doc: PDDocument, val regular: PDType0Font, val bold: PDType0Font) : PdfWriter {
        private var stream: PDPageContentStream? = null
        override fun newPage(): PdfCanvas {
            finishPage()
            val page = PDPage(PDRectangle(CalendarPdfLayout.PAGE_W, CalendarPdfLayout.PAGE_H))
            doc.addPage(page)
            val s = PDPageContentStream(doc, page)
            stream = s
            return BoxCanvas(s, regular, bold)
        }
        override fun finishPage() {
            stream?.close()
            stream = null
        }
    }

    private class BoxCanvas(val s: PDPageContentStream, val regular: PDType0Font, val bold: PDType0Font) : PdfCanvas {
        private val h = CalendarPdfLayout.PAGE_H
        private fun color(argb: Int) = Color(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)
        private fun font(st: PdfText) = if (st.bold) bold else regular

        override fun measure(s: String, style: PdfText): Float = font(style).getStringWidth(s) / 1000f * style.size

        override fun text(s: String, x: Float, y: Float, style: PdfText, align: PdfAlign) {
            val w = measure(s, style)
            val dx = when (align) { PdfAlign.LEFT -> 0f; PdfAlign.CENTER -> w / 2; PdfAlign.RIGHT -> w }
            this.s.beginText()
            this.s.setFont(font(style), style.size)
            this.s.setNonStrokingColor(color(style.color))
            this.s.newLineAtOffset(x - dx, h - y)
            this.s.showText(s)
            this.s.endText()
        }

        private fun path(l: Float, t: Float, r: Float, b: Float, radius: Float) {
            val y0 = h - b; val y1 = h - t
            if (radius <= 0f) { s.addRect(l, y0, r - l, y1 - y0); return }
            val k = 0.5523f * radius
            s.moveTo(l + radius, y0)
            s.lineTo(r - radius, y0); s.curveTo(r - radius + k, y0, r, y0 + radius - k, r, y0 + radius)
            s.lineTo(r, y1 - radius); s.curveTo(r, y1 - radius + k, r - radius + k, y1, r - radius, y1)
            s.lineTo(l + radius, y1); s.curveTo(l + radius - k, y1, l, y1 - radius + k, l, y1 - radius)
            s.lineTo(l, y0 + radius); s.curveTo(l, y0 + radius - k, l + radius - k, y0, l + radius, y0)
            s.closePath()
        }

        override fun rect(left: Float, top: Float, right: Float, bottom: Float, fill: Int?, stroke: Int?, radius: Float, strokeWidth: Float) {
            fill?.let { s.setNonStrokingColor(color(it)); path(left, top, right, bottom, radius); s.fill() }
            stroke?.let { s.setStrokingColor(color(it)); s.setLineWidth(strokeWidth); path(left, top, right, bottom, radius); s.stroke() }
        }

        override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
            s.setStrokingColor(color(color)); s.setLineWidth(width)
            s.moveTo(x1, h - y1); s.lineTo(x2, h - y2); s.stroke()
        }
    }
}
