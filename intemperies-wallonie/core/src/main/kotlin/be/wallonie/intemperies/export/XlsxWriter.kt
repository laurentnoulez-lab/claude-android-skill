package be.wallonie.intemperies.export

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Générateur XLSX (Office Open XML / SpreadsheetML) minimal, sans dépendance :
 * cellules texte, nombre, date, formule ; styles prédéfinis ; fusions ; largeurs ; hauteurs ; mise en page.
 * Volontairement léger (Apache POI ajouterait plusieurs Mo à l'application).
 */
class XlsxWriter {

    /** Styles disponibles (index = position dans cellXfs de styles.xml). */
    enum class Style {
        DEFAULT, CAPTION, TITLE, BOLD, SMALL, HEADER, TEXT, NUMBER, DATE, PERCENT, WRAP, WRAP_SMALL,
        CAL_HEADER, CAL_DRY, CAL_WARNING, CAL_INTEMPERIE, CAL_NO_DATA, CAL_PENDING, CAL_EMPTY,
        ST_DRY, ST_WARNING, ST_INTEMPERIE, ST_NO_DATA, ST_PENDING, BOX, BOLD_NUMBER,
    }

    sealed interface Value {
        data class Text(val s: String) : Value
        data class Num(val v: Double) : Value
        data class Date(val d: LocalDate) : Value
        data class Formula(val f: String) : Value

        /** Texte riche : liste de (texte, gras). */
        data class Rich(val runs: List<Pair<String, Boolean>>) : Value
    }

    private class Cell(val col: Int, val value: Value, val style: Style)

    inner class Sheet internal constructor(val name: String) {
        private val rows = sortedMapOf<Int, MutableList<Cell>>()
        private val heights = mutableMapOf<Int, Double>()
        private val widths = sortedMapOf<Int, Double>()
        private val merges = mutableListOf<String>()
        var landscape = false

        fun set(row: Int, col: Int, value: Value?, style: Style = Style.TEXT) {
            if (value == null) {
                if (style != Style.TEXT && style != Style.DEFAULT) set(row, col, Value.Text(""), style)
                return
            }
            val list = rows.getOrPut(row) { mutableListOf() }
            list.removeAll { it.col == col }
            list += Cell(col, value, style)
        }

        fun text(row: Int, col: Int, s: String, style: Style = Style.TEXT) = set(row, col, Value.Text(s), style)
        fun num(row: Int, col: Int, v: Double?, style: Style = Style.NUMBER) =
            set(row, col, v?.takeIf { !it.isNaN() }?.let { Value.Num(it) }, style)
        fun date(row: Int, col: Int, d: LocalDate, style: Style = Style.DATE) = set(row, col, Value.Date(d), style)
        fun formula(row: Int, col: Int, f: String, style: Style = Style.BOLD_NUMBER) = set(row, col, Value.Formula(f), style)

        fun height(row: Int, points: Double) { heights[row] = points }
        fun width(col: Int, chars: Double) { widths[col] = chars }

        /** Fusionne la plage et applique le style à toutes ses cellules (bordures/fond homogènes). */
        fun merge(r1: Int, c1: Int, r2: Int, c2: Int, style: Style? = null) {
            merges += "${ref(r1, c1)}:${ref(r2, c2)}"
            if (style != null) {
                for (r in r1..r2) for (c in c1..c2) {
                    if (rows[r]?.any { it.col == c } != true) set(r, c, Value.Text(""), style)
                }
            }
        }

        internal fun xml(): String = buildString {
            append(XML_HEADER)
            append("<worksheet xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\">")
            append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
            append("<sheetViews><sheetView workbookViewId=\"0\" zoomScale=\"100\"/></sheetViews>")
            append("<sheetFormatPr defaultRowHeight=\"15\"/>")
            if (widths.isNotEmpty()) {
                append("<cols>")
                widths.forEach { (c, w) -> append("<col min=\"$c\" max=\"$c\" width=\"$w\" customWidth=\"1\"/>") }
                append("</cols>")
            }
            append("<sheetData>")
            val allRows = (rows.keys + heights.keys).toSortedSet()
            for (r in allRows) {
                val h = heights[r]
                append("<row r=\"$r\"")
                if (h != null) append(" ht=\"$h\" customHeight=\"1\"")
                append(">")
                rows[r]?.sortedBy { it.col }?.forEach { cell -> append(cellXml(r, cell)) }
                append("</row>")
            }
            append("</sheetData>")
            if (merges.isNotEmpty()) {
                append("<mergeCells count=\"${merges.size}\">")
                merges.forEach { append("<mergeCell ref=\"$it\"/>") }
                append("</mergeCells>")
            }
            append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>")
            append("<pageSetup paperSize=\"9\" orientation=\"${if (landscape) "landscape" else "portrait"}\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
            append("</worksheet>")
        }

        private fun cellXml(r: Int, c: Cell): String {
            val s = c.style.ordinal
            val ref = ref(r, c.col)
            return when (val v = c.value) {
                is Value.Text -> "<c r=\"$ref\" s=\"$s\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v.s)}</t></is></c>"
                is Value.Num -> "<c r=\"$ref\" s=\"$s\"><v>${fmt(v.v)}</v></c>"
                is Value.Date -> "<c r=\"$ref\" s=\"$s\"><v>${ChronoUnit.DAYS.between(EXCEL_EPOCH, v.d)}</v></c>"
                is Value.Formula -> "<c r=\"$ref\" s=\"$s\"><f>${esc(v.f)}</f></c>"
                is Value.Rich -> "<c r=\"$ref\" s=\"$s\" t=\"inlineStr\"><is>" + v.runs.joinToString("") { (t, b) ->
                    "<r><rPr>${if (b) "<b/>" else ""}<sz val=\"9\"/><color rgb=\"FF1B1B1B\"/><rFont val=\"Calibri\"/></rPr>" +
                        "<t xml:space=\"preserve\">${esc(t)}</t></r>"
                } + "</is></c>"
            }
        }
    }

    private val sheets = mutableListOf<Sheet>()

    /** Ajoute une feuille ; le nom est nettoyé (31 car., caractères interdits) et rendu unique. */
    fun sheet(name: String): Sheet {
        var base = name.replace(Regex("[\\[\\]:*?/\\\\']"), " ").trim().take(31).ifBlank { "Feuille" }
        var candidate = base
        var i = 2
        while (sheets.any { it.name.equals(candidate, ignoreCase = true) }) {
            val suffix = " ($i)"
            candidate = base.take(31 - suffix.length) + suffix
            i++
        }
        return Sheet(candidate).also { sheets += it }
    }

    fun toByteArray(): ByteArray {
        require(sheets.isNotEmpty())
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes())
            put("_rels/.rels", "$XML_HEADER<Relationships xmlns=\"$NS_PKG_REL\"><Relationship Id=\"rId1\" Type=\"$NS_REL/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            put("xl/workbook.xml", workbook())
            put("xl/_rels/workbook.xml.rels", workbookRels())
            put("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", s.xml()) }
        }
        return out.toByteArray()
    }

    private fun contentTypes() = buildString {
        append(XML_HEADER)
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        sheets.indices.forEach {
            append("<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("</Types>")
    }

    private fun workbook() = buildString {
        append(XML_HEADER)
        append("<workbook xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\"><sheets>")
        sheets.forEachIndexed { i, s -> append("<sheet name=\"${esc(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>") }
        append("</sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>")
    }

    private fun workbookRels() = buildString {
        append(XML_HEADER)
        append("<Relationships xmlns=\"$NS_PKG_REL\">")
        sheets.indices.forEach {
            append("<Relationship Id=\"rId${it + 1}\" Type=\"$NS_REL/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>")
        }
        append("<Relationship Id=\"rId${sheets.size + 1}\" Type=\"$NS_REL/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    companion object {
        const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
        private const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"

        fun ref(row: Int, col: Int): String {
            var c = col
            val sb = StringBuilder()
            while (c > 0) {
                val m = (c - 1) % 26
                sb.insert(0, ('A' + m))
                c = (c - 1) / 26
            }
            return "$sb$row"
        }

        private fun fmt(v: Double): String =
            if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.')

        private fun esc(s: String): String = buildString(s.length) {
            for (ch in s) when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\r' -> Unit
                else -> if (ch.code < 0x20 && ch != '\n' && ch != '\t') Unit else append(ch)
            }
        }

        // Polices : 0 normal, 1 gras, 2 titre, 3 légende grise, 4 gras blanc, 5 petit, 6 petit gris italique
        // Remplissages : 0 none, 1 gray125, 2 en-tête, 3 vert, 4 orange, 5 rouge, 6 gris, 7 bleu, 8 encadré
        // Bordures : 0 aucune, 1 fine
        // Formats : 164 "0.0", 165 "dd/mm/yyyy", 9 "0%"
        private fun xf(font: Int, fill: Int, border: Int, numFmt: Int = 0, align: String = "") =
            "<xf numFmtId=\"$numFmt\" fontId=\"$font\" fillId=\"$fill\" borderId=\"$border\" xfId=\"0\"" +
                (if (numFmt != 0) " applyNumberFormat=\"1\"" else "") +
                (if (font != 0) " applyFont=\"1\"" else "") +
                (if (fill != 0) " applyFill=\"1\"" else "") +
                (if (border != 0) " applyBorder=\"1\"" else "") +
                (if (align.isNotEmpty()) " applyAlignment=\"1\"><alignment $align/></xf>" else "/>")

        private const val TOP_WRAP = "vertical=\"top\" wrapText=\"1\""
        private const val CENTER_WRAP = "horizontal=\"center\" vertical=\"center\" wrapText=\"1\""

        private fun fill(rgb: String) = "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF$rgb\"/><bgColor indexed=\"64\"/></patternFill></fill>"

        // L'ordre des <xf> DOIT suivre l'enum Style.
        private val XFS = listOf(
            xf(0, 0, 0), // DEFAULT
            xf(3, 0, 0), // CAPTION
            xf(2, 0, 0), // TITLE
            xf(1, 0, 0), // BOLD
            xf(6, 0, 0, align = TOP_WRAP), // SMALL
            xf(1, 2, 1, align = CENTER_WRAP), // HEADER
            xf(0, 0, 1, align = "vertical=\"center\""), // TEXT
            xf(0, 0, 1, 164, "vertical=\"center\""), // NUMBER
            xf(0, 0, 1, 165, "horizontal=\"left\" vertical=\"center\""), // DATE
            xf(0, 0, 1, 9, "vertical=\"center\""), // PERCENT
            xf(0, 0, 0, align = TOP_WRAP), // WRAP
            xf(5, 0, 0, align = TOP_WRAP), // WRAP_SMALL
            xf(4, 7, 1, align = "horizontal=\"center\" vertical=\"center\""), // CAL_HEADER
            xf(5, 3, 1, align = TOP_WRAP), // CAL_DRY
            xf(5, 4, 1, align = TOP_WRAP), // CAL_WARNING
            xf(5, 5, 1, align = TOP_WRAP), // CAL_INTEMPERIE
            xf(5, 6, 1, align = TOP_WRAP), // CAL_NO_DATA
            xf(5, 0, 1, align = TOP_WRAP), // CAL_PENDING
            xf(0, 8, 1), // CAL_EMPTY
            xf(0, 3, 1, align = "vertical=\"center\""), // ST_DRY
            xf(0, 4, 1, align = "vertical=\"center\""), // ST_WARNING
            xf(1, 5, 1, align = "vertical=\"center\""), // ST_INTEMPERIE
            xf(0, 6, 1, align = "vertical=\"center\""), // ST_NO_DATA
            xf(0, 0, 1, align = "vertical=\"center\""), // ST_PENDING
            xf(5, 8, 0, align = TOP_WRAP), // BOX
            xf(1, 0, 1, 0, "horizontal=\"center\" vertical=\"center\""), // BOLD_NUMBER
        )

        private val STYLES: String = buildString {
            append(XML_HEADER)
            append("<styleSheet xmlns=\"$NS_MAIN\">")
            append("<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"0.0\"/><numFmt numFmtId=\"165\" formatCode=\"dd/mm/yyyy\"/></numFmts>")
            append("<fonts count=\"7\">")
            append("<font><sz val=\"10\"/><color rgb=\"FF1B1B1B\"/><name val=\"Calibri\"/><family val=\"2\"/></font>")
            append("<font><b/><sz val=\"10\"/><color rgb=\"FF1B1B1B\"/><name val=\"Calibri\"/><family val=\"2\"/></font>")
            append("<font><b/><sz val=\"16\"/><color rgb=\"FF1F4E79\"/><name val=\"Calibri\"/><family val=\"2\"/></font>")
            append("<font><b/><sz val=\"9\"/><color rgb=\"FF606C80\"/><name val=\"Calibri\"/><family val=\"2\"/></font>")
            append("<font><b/><sz val=\"10\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/><family val=\"2\"/></font>")
            append("<font><sz val=\"9\"/><color rgb=\"FF1B1B1B\"/><name val=\"Calibri\"/><family val=\"2\"/></font>")
            append("<font><i/><sz val=\"8\"/><color rgb=\"FF606060\"/><name val=\"Calibri\"/><family val=\"2\"/></font>")
            append("</fonts>")
            append("<fills count=\"9\">")
            append("<fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>")
            listOf("E6EAF0", "81C784", "FFB74D", "E57373", "E0E0E0", "1F4E79", "F3F5F8").forEach { append(fill(it)) }
            append("</fills>")
            append("<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>")
            append("<border>")
            listOf("left", "right", "top", "bottom").forEach { append("<$it style=\"thin\"><color rgb=\"FFA0A0A0\"/></$it>") }
            append("<diagonal/></border></borders>")
            append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
            append("<cellXfs count=\"${XFS.size}\">")
            XFS.forEach { append(it) }
            append("</cellXfs>")
            append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
            append("</styleSheet>")
        }

        init {
            check(XFS.size == Style.entries.size) { "Styles XLSX désalignés" }
        }
    }
}
