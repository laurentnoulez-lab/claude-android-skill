package be.wallonie.intemperies

import be.wallonie.intemperies.domain.BelgianHolidays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BelgianHolidaysTest {
    @Test
    fun easterDates() {
        assertEquals(LocalDate.of(2024, 3, 31), BelgianHolidays.easterSunday(2024))
        assertEquals(LocalDate.of(2025, 4, 20), BelgianHolidays.easterSunday(2025))
        assertEquals(LocalDate.of(2026, 4, 5), BelgianHolidays.easterSunday(2026))
    }

    @Test
    fun tenLegalHolidays() {
        val h = BelgianHolidays.of(2026)
        assertEquals(10, h.size)
        assertEquals("Lundi de Pâques", h[LocalDate.of(2026, 4, 6)])
        assertEquals("Ascension", h[LocalDate.of(2026, 5, 14)])
        assertEquals("Lundi de Pentecôte", h[LocalDate.of(2026, 5, 25)])
        assertEquals("Armistice", h[LocalDate.of(2026, 11, 11)])
        assertNull(BelgianHolidays.nameOf(LocalDate.of(2026, 11, 12)))
    }
}
