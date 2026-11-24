package be.wallonie.intemperies.domain

import java.time.LocalDate
import java.time.Month

/**
 * Les 10 jours fériés légaux belges (loi du 4 janvier 1974 relative aux jours fériés).
 * Les jours de remplacement et congés sectoriels (congés du bâtiment) ne sont pas inclus.
 */
object BelgianHolidays {
    private val cache = mutableMapOf<Int, Map<LocalDate, String>>()

    fun of(year: Int): Map<LocalDate, String> = synchronized(cache) {
        cache.getOrPut(year) {
            val easter = easterSunday(year)
            mapOf(
                LocalDate.of(year, Month.JANUARY, 1) to "Nouvel An",
                easter.plusDays(1) to "Lundi de Pâques",
                LocalDate.of(year, Month.MAY, 1) to "Fête du Travail",
                easter.plusDays(39) to "Ascension",
                easter.plusDays(50) to "Lundi de Pentecôte",
                LocalDate.of(year, Month.JULY, 21) to "Fête nationale",
                LocalDate.of(year, Month.AUGUST, 15) to "Assomption",
                LocalDate.of(year, Month.NOVEMBER, 1) to "Toussaint",
                LocalDate.of(year, Month.NOVEMBER, 11) to "Armistice",
                LocalDate.of(year, Month.DECEMBER, 25) to "Noël",
            )
        }
    }

    fun nameOf(date: LocalDate): String? = of(date.year)[date]

    /** Algorithme grégorien anonyme (Meeus/Jones/Butcher). */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }
}
