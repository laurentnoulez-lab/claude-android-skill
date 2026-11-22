package be.wallonie.intemperies.ui

import be.wallonie.intemperies.model.Criteria
import be.wallonie.intemperies.model.DayStatus
import be.wallonie.intemperies.model.Reason
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object Fmt {
    val locale: Locale = Locale.forLanguageTag("fr-BE")
    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy", locale)
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", locale)

    fun num(v: Double, decimals: Int = 1): String = String.format(locale, "%.${decimals}f", v)
    fun hours(v: Double): String = num(v, 1) + " h"
    fun mm(v: Double): String = num(v, 1) + " mm"
    fun temp(v: Double?): String = v?.let { num(it, 1) + " °C" } ?: "—"
    fun date(d: LocalDate): String = d.format(dateFmt)
    fun longDate(d: LocalDate): String = d.format(dayFmt).replaceFirstChar { it.titlecase(locale) }
    fun month(m: YearMonth): String =
        m.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) } + " " + m.year
    fun dow(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { it.titlecase(locale) }

    fun status(s: DayStatus): String = when (s) {
        DayStatus.DRY -> "Pas d'intempérie"
        DayStatus.WARNING -> "Pluie (sans intempérie)"
        DayStatus.INTEMPERIE -> "Intempérie"
        DayStatus.NO_DATA -> "Données insuffisantes"
        DayStatus.IN_PROGRESS -> "Journée en cours"
        DayStatus.FUTURE -> "À venir"
    }

    fun reason(r: Reason, c: Criteria): String {
        val op = if (c.strictComparison) ">" else "≥"
        return when (r) {
            Reason.CONSECUTIVE_RAIN -> "Pluie consécutive $op ${hoursShort(c.consecutiveRainHours)}"
            Reason.TOTAL_RAIN -> "Pluie totale $op ${hoursShort(c.totalRainHours)}"
            Reason.FROST -> "T moy. < ${num(c.frostThresholdC)} °C"
        }
    }

    fun reasonShort(r: Reason): String = when (r) {
        Reason.CONSECUTIVE_RAIN -> "Pluie consécutive"
        Reason.TOTAL_RAIN -> "Pluie totale"
        Reason.FROST -> "Gel"
    }

    fun hoursShort(v: Double): String = if (v % 1.0 == 0.0) "${v.toInt()} h" else hours(v)

    fun criteriaSummary(c: Criteria): String {
        val op = if (c.strictComparison) ">" else "≥"
        val frost = if (c.frostCountsAsIntemperie) " ou T moy. < ${num(c.frostThresholdC)} °C" else ""
        return "Plage ${c.windowStart}–${c.windowEnd} · pas de ${c.stepMinutes} min pluvieux si ≥ ${num(c.wetStepThresholdMm)} mm · " +
            "Intempérie : pluie consécutive $op ${hoursShort(c.consecutiveRainHours)} ou pluie totale $op ${hoursShort(c.totalRainHours)}$frost · " +
            "Orange : pluie ≥ ${hoursShort(c.warningRainHours)}"
    }
}
