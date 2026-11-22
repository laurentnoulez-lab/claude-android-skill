package be.meuse.engis.ui

import be.meuse.engis.domain.FlowClass
import be.meuse.engis.domain.FlowThresholds
import kotlin.math.roundToInt

/** Couleur d'état (ARGB) associée à chaque classe ; toujours accompagnée de son libellé. */
fun FlowClass.argb(): Int = when (this) {
    FlowClass.FAIBLE -> 0xFF898781.toInt()
    FlowClass.NORMAL -> 0xFF0CA30C.toInt()
    FlowClass.ELEVE -> 0xFFFAB219.toInt()
}

/** Ex. « faible < 134 m³/s (70 %) · élevé > 248 m³/s (130 %) ». */
fun FlowThresholds.text(monthMean: Double): String =
    "faible < ${(monthMean * lowPct / 100).roundToInt()} m³/s ($lowPct %) · " +
        "élevé > ${(monthMean * highPct / 100).roundToInt()} m³/s ($highPct %)"
