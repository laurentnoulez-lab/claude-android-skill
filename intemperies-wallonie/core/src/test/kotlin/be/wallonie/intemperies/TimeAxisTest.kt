package be.wallonie.intemperies

import be.wallonie.intemperies.ui.calendar.TimeAxis
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeAxisTest {
    private fun h(hours: Int, minutes: Int = 0) = hours * 3600 + minutes * 60

    @Test
    fun defaultWindowHasHourlyTicks() {
        val t = TimeAxis.ticks(h(7, 30), h(16), widthPx = 900f, minSpacingPx = 90f)
        assertEquals((8..16).map { h(it) }, t)
        assertEquals("8h", TimeAxis.label(t.first()))
    }

    @Test
    fun axisFollowsUserWindow() {
        assertEquals(listOf(h(6), h(7), h(8), h(9), h(10)), TimeAxis.ticks(h(6), h(10), 900f, 90f))
        // Plage longue sur écran étroit : graduations espacées de 2 h ou plus.
        val long = TimeAxis.ticks(h(6), h(20), widthPx = 300f, minSpacingPx = 60f)
        assertEquals(listOf(h(6), h(9), h(12), h(15), h(18)), long)
    }

    @Test
    fun shortWindowWithoutFullHourShowsBounds() {
        assertEquals(listOf(h(8)), TimeAxis.ticks(h(7, 30), h(8, 15), 300f, 60f))
        assertEquals(listOf(h(7, 10), h(7, 50)), TimeAxis.ticks(h(7, 10), h(7, 50), 300f, 60f))
        assertEquals("7h30", TimeAxis.label(h(7, 30)))
    }

    @Test
    fun invalidWindowGivesNoTicks() {
        assertEquals(emptyList<Int>(), TimeAxis.ticks(h(16), h(7, 30), 300f, 60f))
    }
}
