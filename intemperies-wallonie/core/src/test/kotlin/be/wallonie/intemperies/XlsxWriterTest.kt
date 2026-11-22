package be.wallonie.intemperies

import be.wallonie.intemperies.domain.DayClassifier
import be.wallonie.intemperies.export.CalendarWorkbook
import be.wallonie.intemperies.export.XlsxWriter
import be.wallonie.intemperies.model.Commune
import be.wallonie.intemperies.model.Criteria
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.model.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.zip.ZipInputStream

class XlsxWriterTest {
    @Test
    fun cellReferences() {
        assertEquals("A1", XlsxWriter.ref(1, 1))
        assertEquals("Z9", XlsxWriter.ref(9, 26))
        assertEquals("AA10", XlsxWriter.ref(10, 27))
        assertEquals("AM3", XlsxWriter.ref(3, 39))
    }

    @Test
    fun sheetNamesAreSanitizedAndUnique() {
        val x = XlsxWriter()
        val a = x.sheet("Ottignies/Louvain-la-Neuve : [test] très long nom de commune")
        val b = x.sheet(a.name)
        assertTrue(a.name.length <= 31)
        assertTrue(a.name.none { it in "[]:*?/\\" })
        assertTrue(b.name != a.name && b.name.length <= 31)
    }

    @Test
    fun workbookPackageContainsAllParts() {
        val zone = ZoneId.of("Europe/Brussels")
        val month = YearMonth.of(2026, 10)
        val days = (1..month.lengthOfMonth()).map { d ->
            val date = month.atDay(d)
            val start = date.atStartOfDay(zone).toEpochSecond()
            val rain = (0 until 288).map { Sample(start + it * 300L, if (d % 5 == 0 && it in 96..160) 0.2 else 0.0, 40) }
            DayClassifier.classify(date, rain, emptyList(), Criteria(), zone, Instant.parse("2026-11-22T10:00:00Z"))
        }
        val report = MonthReport(Commune("25112", "Wavre", "Brabant wallon", 50.7, 4.6), month, null, null, days, Criteria(), null)
        val bytes = CalendarWorkbook.build(listOf(report), "22/11/2026 10:00")
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e -> entries[e.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertTrue(entries.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml")))
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("COUNTIF("))
        assertTrue(sheet.contains("Sources des données : SPW"))
        assertTrue(entries.getValue("xl/workbook.xml").contains("Base réglementaire"))
    }
}
