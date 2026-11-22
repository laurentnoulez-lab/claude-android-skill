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
            Reason.CONSECUTIVE_RAIN -> "Précipitations consécutives $op ${hoursShort(c.consecutiveRainHours)}"
            Reason.TOTAL_RAIN -> "Précipitations totales $op ${hoursShort(c.totalRainHours)}"
            Reason.COLD -> "T < ${deg(c.coldThresholdC)} pendant ≥ ${hoursShort(c.coldMinHours)}"
            Reason.HEAT -> "T > ${deg(c.heatThresholdC)} pendant ≥ ${hoursShort(c.heatMinHours)}"
        }
    }

    fun reasonShort(r: Reason): String = when (r) {
        Reason.CONSECUTIVE_RAIN -> "Précip. consécutives"
        Reason.TOTAL_RAIN -> "Précip. totales"
        Reason.COLD -> "Gel"
        Reason.HEAT -> "Canicule"
    }

    /** Température : « 0 °C », « -2,5 °C ». */
    fun deg(v: Double): String = (if (v % 1.0 == 0.0) v.toInt().toString() else num(v)) + " °C"

    fun hoursShort(v: Double): String = if (v % 1.0 == 0.0) "${v.toInt()} h" else hours(v)

    fun criteriaSummary(c: Criteria): String {
        val op = if (c.strictComparison) ">" else "≥"
        val temp = buildList {
            if (c.coldCountsAsIntemperie) add(reason(Reason.COLD, c))
            if (c.heatCountsAsIntemperie) add(reason(Reason.HEAT, c))
        }.joinToString("") { " ou $it" }
        val info = buildList {
            if (!c.coldCountsAsIntemperie) add("gel T < ${deg(c.coldThresholdC)} ≥ ${hoursShort(c.coldMinHours)}")
            if (!c.heatCountsAsIntemperie) add("canicule T > ${deg(c.heatThresholdC)} ≥ ${hoursShort(c.heatMinHours)}")
        }
        return "Plage ${c.windowStart}–${c.windowEnd} · pas de ${c.stepMinutes} min avec précipitations si ≥ ${num(c.wetStepThresholdMm)} mm · " +
            "Intempérie : précipitations consécutives $op ${hoursShort(c.consecutiveRainHours)} ou totales $op ${hoursShort(c.totalRainHours)}$temp · " +
            "Orange : précipitations ≥ ${hoursShort(c.warningRainHours)}" +
            (if (info.isNotEmpty()) " · Signalé sans être bloquant : ${info.joinToString(", ")}" else "")
    }
}
