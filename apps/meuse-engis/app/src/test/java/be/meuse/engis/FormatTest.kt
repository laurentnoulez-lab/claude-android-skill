package be.meuse.engis

import be.meuse.engis.ui.ago
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class FormatTest {
    private val now = ZonedDateTime.parse("2026-11-22T09:52:00+01:00[Europe/Brussels]")

    @Test
    fun ago_isReadable() {
        assertEquals("à l'instant", ago(now.toInstant(), now))
        assertEquals("il y a 52 min", ago(now.minusMinutes(52).toInstant(), now))
        assertEquals("il y a 1 h 05", ago(now.minusMinutes(65).toInstant(), now))
        assertEquals("il y a 2 j", ago(now.minusDays(2).toInstant(), now))
    }
}
