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

    private val zone: java.time.ZoneId = java.time.ZoneId.of("Europe/Brussels")
    private val dateTimeFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm", locale)

    /** « 22/11/2026 à 10:40 » (heure belge). */
    fun dateTime(epochSec: Long): String = java.time.Instant.ofEpochSecond(epochSec).atZone(zone).format(dateTimeFmt)

    /** Fin de la dernière mesure disponible (la valeur horodatée 10:35 couvre 10:35–10:40). */
    fun rainDataEnd(epochSec: Long): Long = epochSec + 300
    fun temperatureDataEnd(epochSec: Long): Long = epochSec + 3600

    /** Plus aucune nouvelle mesure de pluie depuis plus de [STALE_HOURS] heures. */
    const val STALE_HOURS = 3
    fun isStale(lastRainEpochSec: Long?, nowSec: Long = java.time.Instant.now().epochSecond): Boolean =
        lastRainEpochSec == null || nowSec - rainDataEnd(lastRainEpochSec) > STALE_HOURS * 3600L

    /** Ligne de fraîcheur des données d'un rapport mensuel. */
    /** « T moy. 2,3 °C (min 0,5 / max 4,1) » sur la plage de travail ; « T non disponible » sinon. */
    fun tempSummary(d: be.wallonie.intemperies.model.DayReport): String {
        val min = d.minTemperatureC
        val max = d.maxTemperatureC
        val mean = d.meanTemperatureC ?: return if (min != null && max != null) {
            "T ${temp(min)} à ${temp(max)} (journée en cours)"
        } else {
            "T non disponible"
        }
        return "T moy. ${temp(mean)}" + if (min != null && max != null) " (min ${temp(min)} / max ${temp(max)})" else ""
    }

    /** Résumé d'une journée : précipitations, température et motif d'intempérie éventuel. */
    fun daySummary(d: be.wallonie.intemperies.model.DayReport): String =
        "${hours(d.rainHours)} de pluie · ${mm(d.windowRainMm)} · ${tempSummary(d)}"

    /**
     * Alertes de fiabilité d'une commune : pas de pluviomètre, pas de station de température dans le rayon autorisé,
     * station de température sans mesure récente. Reprises dans l'application, le PDF et l'Excel.
     */
    fun stationAlerts(r: be.wallonie.intemperies.model.MonthReport, nowSec: Long = java.time.Instant.now().epochSecond): List<String> = buildList {
        if (r.rainStation == null) {
            add("Aucun pluviomètre retenu pour cette commune : précipitations à contrôler manuellement.")
        }
        val t = r.temperatureStation
        if (t == null) {
            add(
                "Aucune station de température à moins de ${num(r.criteria.maxTemperatureDistanceKm, 0)} km : augmentez la distance " +
                    "maximale (Paramètres › Température — station) ou contrôlez les températures manuellement.",
            )
        } else {
            val last = r.lastTemperatureDataEpochSec
            if (last == null || nowSec - temperatureDataEnd(last) > TEMP_STALE_HOURS * 3600L) {
                add("Station de température ${t.station.name} sans mesure depuis plus de $TEMP_STALE_HOURS h : températures à contrôler manuellement.")
            }
        }
    }

    const val TEMP_STALE_HOURS = 6

    fun freshness(r: be.wallonie.intemperies.model.MonthReport): String = buildString {
        append("Mesures disponibles jusqu'au ")
        append(r.lastRainDataEpochSec?.let { dateTime(rainDataEnd(it)) + " (pluie)" } ?: "— (pluie)")
        r.lastTemperatureDataEpochSec?.let { append(", ").append(dateTime(temperatureDataEnd(it))).append(" (température)") }
        append(" · mise à jour depuis le SPW le ")
        append(r.lastSyncEpochSec?.let { dateTime(it) } ?: "—")
    }
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

    /** mm avec 2 décimales seulement si nécessaire : 0,3 / 0,25 / 0,125 → 0,13. */
    fun mmPrecise(v: Double): String {
        val r1 = Math.round(v * 10) / 10.0
        return if (kotlin.math.abs(v - r1) < 1e-9) num(v, 1) else num(v, 2)
    }

    /** « 0,5 mm/h » */
    fun intensity(c: Criteria): String = "${mmPrecise(c.wetIntensityMmH)} mm/h"

    /** Seuil court pour le graphique : « 0,25 mm / 30 min » */
    fun wetThreshold(c: Criteria): String = "${mmPrecise(c.wetStepThresholdMm)} mm / ${c.stepMinutes} min"

    /** « le plus souvent ≥ 0,3 mm » si le cumul théorique n'est pas un multiple de 0,1 mm, sinon vide. */
    fun practicalNote(c: Criteria): String =
        if (kotlin.math.abs(c.effectiveStepThresholdMm - c.wetStepThresholdMm) < 1e-9) ""
        else " (mesures SPW le plus souvent par paliers de 0,1 mm : seuil atteint en général à ${num(c.effectiveStepThresholdMm, 1)} mm)"

    /** « intensité ≥ 0,5 mm/h, soit un cumul ≥ 0,25 mm sur un pas de 30 min (… en pratique ≥ 0,3 mm) » */
    fun wetThresholdLong(c: Criteria): String =
        "intensité ≥ ${intensity(c)}, soit un cumul ≥ ${mmPrecise(c.wetStepThresholdMm)} mm sur un pas de ${c.stepMinutes} min${practicalNote(c)}"

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
        return "Plage ${c.windowStart}–${c.windowEnd} · un pas de ${c.stepMinutes} min compte comme précipitation si ${wetThresholdLong(c)} · " +
            "Intempérie : précipitations consécutives $op ${hoursShort(c.consecutiveRainHours)} ou totales $op ${hoursShort(c.totalRainHours)}$temp · " +
            "Orange : précipitations ≥ ${hoursShort(c.warningRainHours)}" +
            (if (info.isNotEmpty()) " · Signalé sans être bloquant : ${info.joinToString(", ")}" else "")
    }
}
