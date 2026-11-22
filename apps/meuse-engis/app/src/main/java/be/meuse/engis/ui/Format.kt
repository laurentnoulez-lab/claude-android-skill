package be.meuse.engis.ui

import be.meuse.engis.domain.Trend
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

val FR: Locale = Locale.FRANCE
val BRUSSELS: ZoneId = ZoneId.of("Europe/Brussels")
val HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", FR)
val DAY_HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM 'à' HH:mm", FR)
val DAY_LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", FR)

fun flow(v: Double): String = "${v.roundToInt()} m³/s"
fun level(v: Double): String = String.format(FR, "%.2f m", v)
fun monthName(d: LocalDate): String = d.month.getDisplayName(TextStyle.FULL, FR)

fun Trend.label(): String = when (this) {
    Trend.HAUSSE -> "↑ en hausse"
    Trend.BAISSE -> "↓ en baisse"
    Trend.STABLE -> "→ stable"
    Trend.INCONNUE -> "tendance inconnue"
}
