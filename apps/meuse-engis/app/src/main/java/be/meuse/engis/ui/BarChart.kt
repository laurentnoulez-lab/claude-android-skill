package be.meuse.engis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.meuse.engis.domain.DayBar
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private val DAY_SHORT = DateTimeFormatter.ofPattern("EEE", FR)

/**
 * Histogramme du débit moyen journalier (une barre par jour, la dernière = jour en cours, hachurée)
 * avec une ligne horizontale de référence (moyenne du mois).
 */
@Composable
fun DailyFlowChart(
    bars: List<DayBar>,
    reference: Double?,
    referenceLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChartColors.current
    val measurer = rememberTextMeasurer()
    var selected by remember(bars) { mutableStateOf(bars.lastIndex) }

    val sel = bars.getOrNull(selected)
    Column(modifier) {
        // Détail de la barre touchée (par défaut : aujourd'hui)
        if (sel != null) {
            val dayTxt = sel.date.format(DAY_LONG).replaceFirstChar { it.uppercase() }
            val valueTxt = sel.mean?.let { flow(it) } ?: "pas de donnée"
            val suffix = when {
                sel.isToday -> " (moyenne provisoire sur ${sel.hours} h)"
                sel.hours in 1..23 -> " (incomplet : ${sel.hours} h)"
                else -> ""
            }
            Text(
                "$dayTxt : $valueTxt$suffix",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkPrimary,
            )
        }

        val description = buildString {
            append("Débit moyen journalier. ")
            bars.forEach { b ->
                append(b.date.format(DAY_LONG)).append(" : ")
                append(b.mean?.let { flow(it) } ?: "pas de donnée").append(". ")
            }
            reference?.let { append("$referenceLabel : ${flow(it)}.") }
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .semantics { contentDescription = description }
                .pointerInput(bars) {
                    detectTapGestures { pos ->
                        val left = 40.dp.toPx()
                        val slot = (size.width - left) / bars.size
                        val i = ((pos.x - left) / slot).toInt()
                        if (i in bars.indices) selected = i
                    }
                },
        ) {
            drawChart(bars, reference, selected, measurer, colors)
        }
        if (reference != null) {
            // Légende de la ligne de référence (hors du tracé : ne masque jamais une barre)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.width(28.dp).height(12.dp)) {
                    drawLine(
                        colors.inkPrimary,
                        Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "$referenceLabel : ${flow(reference)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.inkPrimary,
                )
            }
        }
    }
}

private fun DrawScope.drawChart(
    bars: List<DayBar>,
    reference: Double?,
    selected: Int,
    measurer: TextMeasurer,
    c: ChartColors,
) {
    val axisStyle = TextStyle(color = c.inkMuted, fontSize = 11.sp)
    val valueStyle = TextStyle(color = c.inkSecondary, fontSize = 11.sp)
    val valueStyleSel = TextStyle(color = c.inkPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)

    val left = 40.dp.toPx()
    val top = 22.dp.toPx()
    val bottom = size.height - 34.dp.toPx()
    val plotH = bottom - top
    val slot = (size.width - left) / bars.size
    val barW = minOf(24.dp.toPx(), slot * 0.6f)

    val maxData = (bars.mapNotNull { it.mean } + listOfNotNull(reference)).maxOrNull() ?: 1.0
    val step = niceStep(maxData / 4)
    val yMax = ceil(maxData * 1.12 / step) * step
    fun y(v: Double) = bottom - (v / yMax * plotH).toFloat()

    // Grille + graduations (discrètes)
    var t = 0.0
    while (t <= yMax + 1e-9) {
        val yy = y(t)
        drawLine(c.grid, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1.dp.toPx())
        val lbl = measurer.measure(t.roundToInt().toString(), axisStyle)
        drawText(lbl, topLeft = Offset(left - 6.dp.toPx() - lbl.size.width, yy - lbl.size.height / 2))
        t += step
    }
    val unit = measurer.measure("m³/s", axisStyle)
    drawText(unit, topLeft = Offset(0f, 0f))

    // Ligne de référence (moyenne du mois), tiretée, sous les barres et leurs valeurs
    if (reference != null) {
        val yr = y(reference)
        drawLine(
            c.inkPrimary,
            Offset(left, yr), Offset(size.width, yr),
            strokeWidth = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())),
        )
    }

    // Barres
    val radius = 4.dp.toPx()
    bars.forEachIndexed { i, b ->
        val cx = left + slot * i + slot / 2
        val dayLbl = if (b.isToday) "auj." else b.date.format(DAY_SHORT).removeSuffix(".")
        val l1 = measurer.measure(dayLbl, if (i == selected) valueStyleSel else axisStyle)
        val l2 = measurer.measure(b.date.dayOfMonth.toString(), axisStyle)
        drawText(l1, topLeft = Offset(cx - l1.size.width / 2, bottom + 4.dp.toPx()))
        drawText(l2, topLeft = Offset(cx - l2.size.width / 2, bottom + 4.dp.toPx() + l1.size.height))

        val v = b.mean ?: return@forEachIndexed
        val yTop = y(v)
        val rect = RoundRect(
            left = cx - barW / 2, top = yTop, right = cx + barW / 2, bottom = bottom,
            topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
        )
        val path = Path().apply { addRoundRect(rect) }
        if (b.isToday) {
            // Jour en cours : contour + hachures à 45° (valeur provisoire)
            drawPath(path, c.bar.copy(alpha = 0.18f))
            clipPath(path) {
                val gap = 6.dp.toPx()
                var x = rect.left - (rect.bottom - rect.top)
                while (x < rect.right) {
                    drawLine(
                        c.bar,
                        Offset(x, rect.bottom), Offset(x + (rect.bottom - rect.top), rect.top),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    x += gap
                }
            }
            drawPath(path, c.bar, style = Stroke(width = 1.5.dp.toPx()))
        } else {
            drawPath(path, c.bar)
        }
        if (i == selected) {
            drawRoundRect(
                c.inkPrimary,
                topLeft = Offset(rect.left - 3.dp.toPx(), yTop - 3.dp.toPx()),
                size = Size(barW + 6.dp.toPx(), bottom - yTop + 3.dp.toPx()),
                cornerRadius = CornerRadius(radius + 2.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        val vl = measurer.measure(v.roundToInt().toString(), if (i == selected) valueStyleSel else valueStyle)
        val vTop = yTop - vl.size.height - 4.dp.toPx()
        drawRect(
            c.surface,
            topLeft = Offset(cx - vl.size.width / 2 - 2.dp.toPx(), vTop),
            size = Size(vl.size.width + 4.dp.toPx(), vl.size.height.toFloat()),
        )
        drawText(vl, topLeft = Offset(cx - vl.size.width / 2, vTop))
    }

}

/** Pas de graduation "rond" (1, 2, 2.5, 5 × 10^n). */
internal fun niceStep(raw: Double): Double {
    if (raw <= 0) return 1.0
    val mag = 10.0.pow(floor(log10(raw)))
    val n = raw / mag
    val nice = when {
        n <= 1 -> 1.0
        n <= 2 -> 2.0
        n <= 2.5 -> 2.5
        n <= 5 -> 5.0
        else -> 10.0
    }
    return nice * mag
}
