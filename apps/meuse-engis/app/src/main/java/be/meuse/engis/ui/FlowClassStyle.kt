package be.meuse.engis.ui

import be.meuse.engis.domain.DayStats
import be.meuse.engis.domain.FlowClass
import java.time.LocalDate
import kotlin.math.roundToInt

/** Couleur d'état (ARGB) associée à chaque classe ; toujours accompagnée de son libellé. */
fun FlowClass.argb(): Int = when (this) {
    FlowClass.FAIBLE -> 0xFF898781.toInt()
    FlowClass.NORMAL -> 0xFF0CA30C.toInt()
    FlowClass.FORT -> 0xFFFAB219.toInt()
    FlowClass.TRES_FORT -> 0xFFD03B3B.toInt()
}

/** Seuils lisibles, ex. « Pour un 22 novembre : faible < 66 · fort > 330 · très fort > 380 m³/s ». */
fun DayStats.thresholdsText(day: LocalDate): String =
    "Pour un ${day.dayOfMonth} ${monthName(day)} : faible < ${p15.roundToInt()} · " +
        "fort > ${p85.roundToInt()} · très fort > ${p90.roundToInt()} m³/s"
