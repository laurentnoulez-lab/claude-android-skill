package be.wallonie.intemperies.ui.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import be.wallonie.intemperies.data.WeatherRepository
import be.wallonie.intemperies.domain.DayClassifier
import be.wallonie.intemperies.model.Criteria
import be.wallonie.intemperies.model.DayReport
import be.wallonie.intemperies.ui.Fmt
import java.time.Instant
import java.time.LocalTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Graphique de la journée de travail : précipitations par pas d'analyse (haut) et température horaire (bas).
 * Deux graphiques empilés partageant le même axe horaire (pas de double axe Y). Toucher / glisser = lecture.
 */
@Composable
fun DayChart(day: DayReport, criteria: Criteria, temperatureStationLabel: String?, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val rainColor = if (dark) Color(0xFF3987E5) else Color(0xFF2A78D6)
    val tempColor = if (dark) Color(0xFFD95926) else Color(0xFFEB6834)
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val measurer = rememberTextMeasurer()

    val ws = criteria.windowStart.toSecondOfDay().toDouble()
    val we = criteria.windowEnd.toSecondOfDay().toDouble()
    val stepSec = criteria.stepMinutes.coerceAtLeast(5) * 60.0
    val zone = WeatherRepository.BRUSSELS
    // Températures exprimées en secondes depuis minuit (début d'heure), limitées à la plage.
    val temps = day.temperaturePoints.map {
        Instant.ofEpochSecond(it.epochSec).atZone(zone).toLocalTime().toSecondOfDay().toDouble() to it.value
    }
    var selected by remember(day.date) { mutableStateOf<Double?>(null) }
    var showTable by remember(day.date) { mutableStateOf(false) }

    val summary = "Graphique : ${Fmt.mm(day.windowRainMm)} de précipitations sur ${Fmt.hours(day.rainHours)} " +
        "entre ${criteria.windowStart} et ${criteria.windowEnd}" +
        (day.meanTemperatureC?.let { ", température moyenne ${Fmt.temp(it)}" } ?: "")

    Column(modifier.fillMaxWidth().semantics { contentDescription = summary }) {
        // Lecture (remplace l'infobulle au survol sur mobile).
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(surface).padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            val t = selected
            Text(
                if (t == null) "Touchez ou glissez sur le graphique pour lire les valeurs" else readout(t, ws, we, stepSec, day, temps, criteria),
                style = MaterialTheme.typography.bodySmall,
                color = if (t == null) muted else ink,
            )
        }
        Spacer(Modifier.height(8.dp))
        Legend(rainColor, tempColor, muted)
        Spacer(Modifier.height(4.dp))

        val gesture = Modifier
            .pointerInput(day.date, criteria) {
                detectTapGestures { o -> selected = xToTime(o.x, size.width.toFloat(), GUTTER.toPx(), END_PAD.toPx(), ws, we) }
            }
            .pointerInput(day.date, criteria) {
                detectHorizontalDragGestures(onDragEnd = {}) { change, _ ->
                    selected = xToTime(change.position.x, size.width.toFloat(), GUTTER.toPx(), END_PAD.toPx(), ws, we)
                }
            }

        Text("Précipitations : cumul en mm par pas de ${criteria.stepMinutes} min", style = MaterialTheme.typography.labelMedium, color = ink)
        Canvas(Modifier.fillMaxWidth().height(120.dp).then(gesture)) {
            drawRain(day, criteria, ws, we, stepSec, rainColor, ink, muted, grid, measurer, selected)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Température de l'air (moyennes horaires, °C)" + (temperatureStationLabel?.let { " — $it" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = ink,
        )
        if (temps.isEmpty()) {
            Text(
                "Température non disponible pour cette journée.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Canvas(Modifier.fillMaxWidth().height(140.dp).then(gesture)) {
                drawTemperature(temps, criteria, ws, we, tempColor, ink, muted, grid, surface, measurer, selected)
            }
        }
        TextButton(onClick = { showTable = !showTable }) {
            Text(if (showTable) "Masquer les valeurs" else "Afficher les valeurs (tableau)")
        }
        if (showTable) ValuesTable(day, criteria, ws, stepSec, temps)
    }
}

private val GUTTER = 40.dp
private val END_PAD = 8.dp
private val AXIS_H = 16.dp

private fun xToTime(x: Float, width: Float, gutter: Float, endPad: Float, ws: Double, we: Double): Double {
    val plotW = width - gutter - endPad
    val f = ((x - gutter) / plotW).coerceIn(0f, 1f)
    return ws + f * (we - ws)
}

private fun hhmm(sec: Double): String = LocalTime.ofSecondOfDay(sec.toLong().coerceIn(0, 86_399)).toString()

private fun readout(
    t: Double, ws: Double, we: Double, stepSec: Double, day: DayReport, temps: List<Pair<Double, Double>>, c: Criteria,
): String {
    val i = floor((t - ws) / stepSec).toInt().coerceIn(0, max(0, day.stepRainMm.size - 1))
    val s = ws + i * stepSec
    val e = min(s + stepSec, we)
    val mm = day.stepRainMm.getOrNull(i)
    val rain = when {
        mm == null -> "—"
        mm.isNaN() -> "pas de mesure"
        else -> Fmt.mm(mm) + if (day.wetSteps.getOrNull(i) == true) " (≥ ${Fmt.wetThreshold(c)} : pas compté)" else " (< ${Fmt.wetThreshold(c)} : pas sec)"
    }
    val temp = temps.firstOrNull { (h, _) -> t >= h && t < h + 3600 }
        ?.let { (h, v) -> " · T ${Fmt.temp(v)} (moyenne ${hhmm(h)}–${hhmm(h + 3600)})" } ?: ""
    return "${hhmm(s)}–${hhmm(e)} · précipitations $rain$temp"
}

@Composable
private fun Legend(rain: Color, temp: Color, muted: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(rain))
        Text(" pas compté", style = MaterialTheme.typography.labelSmall, color = muted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(rain.copy(alpha = 0.35f)))
        Text(" sous le seuil", style = MaterialTheme.typography.labelSmall, color = muted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(width = 14.dp, height = 2.dp).background(temp))
        Text(" température", style = MaterialTheme.typography.labelSmall, color = muted)
        Spacer(Modifier.width(10.dp))
        Text("- - seuils", style = MaterialTheme.typography.labelSmall, color = muted)
    }
}

/** Valeur « ronde » supérieure pour l'échelle des précipitations. */
private fun niceCeil(v: Double): Double {
    val steps = listOf(0.2, 0.4, 0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 8.0, 10.0, 15.0, 20.0, 30.0, 50.0)
    return steps.firstOrNull { it >= v } ?: (ceil(v / 10) * 10)
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, x: Float, y: Float, color: Color, alignEnd: Boolean = false, bold: Boolean = false) {
    val layout = measurer.measure(text, TextStyle(fontSize = 10.sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
    val dx = if (alignEnd) x - layout.size.width else x
    drawText(layout, topLeft = Offset(dx, y - layout.size.height / 2f))
}

private fun DrawScope.timeAxis(measurer: TextMeasurer, ws: Double, we: Double, left: Float, right: Float, baseY: Float, muted: Color, grid: Color, withLabels: Boolean) {
    val plotW = right - left
    // Graduations dérivées de la plage paramétrée : l'axe s'adapte à toute durée de travail.
    for (tick in TimeAxis.ticks(ws.toInt(), we.toInt(), plotW, 30.dp.toPx())) {
        val x = left + ((tick - ws) / (we - ws)).toFloat() * plotW
        drawLine(grid, Offset(x, baseY), Offset(x, baseY + 3.dp.toPx()), strokeWidth = 1.dp.toPx())
        if (withLabels) {
            val layout = measurer.measure(TimeAxis.label(tick), TextStyle(fontSize = 10.sp, color = muted))
            val lx = (x - layout.size.width / 2f).coerceIn(left - GUTTER.toPx() / 2, size.width - layout.size.width.toFloat())
            drawText(layout, topLeft = Offset(lx, baseY + 4.dp.toPx()))
        }
    }
}

private fun DrawScope.crosshair(selected: Double?, ws: Double, we: Double, left: Float, right: Float, top: Float, bottom: Float, ink: Color) {
    if (selected == null) return
    val x = left + ((selected - ws) / (we - ws)).toFloat() * (right - left)
    drawLine(ink.copy(alpha = 0.6f), Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
}

private fun DrawScope.drawRain(
    day: DayReport, c: Criteria, ws: Double, we: Double, stepSec: Double,
    color: Color, ink: Color, muted: Color, grid: Color, measurer: TextMeasurer, selected: Double?,
) {
    val left = GUTTER.toPx()
    val right = size.width - END_PAD.toPx()
    val top = 8.dp.toPx()
    val base = size.height - AXIS_H.toPx()
    val plotH = base - top
    val values = day.stepRainMm.filter { !it.isNaN() }
    val yMax = niceCeil(max(values.maxOrNull() ?: 0.0, c.wetStepThresholdMm * 2).coerceAtLeast(0.2))
    fun y(v: Double) = base - (v / yMax).toFloat() * plotH
    fun x(t: Double) = left + ((t - ws) / (we - ws)).toFloat() * (right - left)

    // Grille horizontale discrète + étiquettes Y.
    for (v in listOf(0.0, yMax / 2, yMax)) {
        drawLine(grid, Offset(left, y(v)), Offset(right, y(v)), strokeWidth = 1.dp.toPx())
        label(measurer, Fmt.num(v, if (yMax < 2) 1 else 0), left - 4.dp.toPx(), y(v), muted, alignEnd = true)
    }
    val gap = 1.dp.toPx() // 2 dp entre barres voisines
    day.stepRainMm.forEachIndexed { i, mm ->
        val s = ws + i * stepSec
        val e = min(s + stepSec, we)
        val x0 = x(s) + gap
        val x1 = x(e) - gap
        if (mm.isNaN()) {
            // Pas sans mesure : marque grise discrète sur la ligne de base.
            drawLine(muted.copy(alpha = 0.5f), Offset(x0, base - 1.dp.toPx()), Offset(x1, base - 1.dp.toPx()), strokeWidth = 2.dp.toPx())
            return@forEachIndexed
        }
        if (mm <= 0.0) return@forEachIndexed
        val h = (base - y(mm)).coerceAtLeast(2.dp.toPx())
        val wet = day.wetSteps.getOrNull(i) == true
        val isSel = selected != null && selected >= s && selected < e
        val fill = if (wet) color else color.copy(alpha = 0.35f)
        val r = min(4.dp.toPx(), min(h, x1 - x0) / 2)
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = x0, top = base - h, right = x1, bottom = base,
                    topLeftCornerRadius = CornerRadius(r, r), topRightCornerRadius = CornerRadius(r, r),
                    bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
                ),
            )
        }
        drawPath(path, fill)
        if (isSel) drawPath(path, ink, style = Stroke(width = 1.5.dp.toPx()))
    }
    // Seuil d'un pas « avec précipitations ».
    val ty = y(c.wetStepThresholdMm)
    drawLine(muted, Offset(left, ty), Offset(right, ty), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    label(measurer, "seuil ${Fmt.wetThreshold(c)}", right, ty - 8.dp.toPx(), muted, alignEnd = true)
    drawLine(ink.copy(alpha = 0.5f), Offset(left, base), Offset(right, base), strokeWidth = 1.dp.toPx())
    timeAxis(measurer, ws, we, left, right, base, muted, grid, withLabels = true)
    crosshair(selected, ws, we, left, right, top, base, ink)
}

private fun DrawScope.drawTemperature(
    temps: List<Pair<Double, Double>>, c: Criteria, ws: Double, we: Double,
    color: Color, ink: Color, muted: Color, grid: Color, surface: Color, measurer: TextMeasurer, selected: Double?,
) {
    val left = GUTTER.toPx()
    val right = size.width - END_PAD.toPx()
    val top = 10.dp.toPx()
    val base = size.height - AXIS_H.toPx()
    val plotH = base - top
    val vals = temps.map { it.second }
    var lo = floor(vals.min() - 1)
    var hi = ceil(vals.max() + 1)
    // Les seuils ne sont tracés que s'ils sont proches des valeurs du jour (sinon l'échelle serait écrasée).
    val showCold = c.coldThresholdC in (lo - 6)..(hi + 1)
    val showHeat = c.heatThresholdC in (lo - 1)..(hi + 6)
    if (showCold) lo = min(lo, c.coldThresholdC - 1)
    if (showHeat) hi = max(hi, c.heatThresholdC + 1)
    if (hi - lo < 4) hi = lo + 4
    fun y(v: Double) = base - ((v - lo) / (hi - lo)).toFloat() * plotH
    fun x(t: Double) = left + ((t - ws) / (we - ws)).toFloat() * (right - left)

    val mid = Math.round((lo + hi) / 2.0).toDouble()
    for (v in listOf(lo, mid, hi)) {
        drawLine(grid, Offset(left, y(v)), Offset(right, y(v)), strokeWidth = 1.dp.toPx())
        label(measurer, "${v.toInt()}°", left - 4.dp.toPx(), y(v), muted, alignEnd = true)
    }
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    if (showCold) {
        drawLine(muted, Offset(left, y(c.coldThresholdC)), Offset(right, y(c.coldThresholdC)), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        label(measurer, "gel ${Fmt.deg(c.coldThresholdC)}", right, thresholdLabelY(y(c.coldThresholdC), top), muted, alignEnd = true)
    }
    if (showHeat) {
        drawLine(muted, Offset(left, y(c.heatThresholdC)), Offset(right, y(c.heatThresholdC)), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        label(measurer, "canicule ${Fmt.deg(c.heatThresholdC)}", right, thresholdLabelY(y(c.heatThresholdC), top), muted, alignEnd = true)
    }
    // Chaque moyenne horaire est placée au milieu de la partie de l'heure comprise dans la plage.
    val pts = temps.map { (h, v) ->
        val s = max(h, ws)
        val e = min(h + 3600, we)
        Offset(x((s + e) / 2), y(v))
    }
    if (pts.size > 1) {
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    pts.forEachIndexed { i, p ->
        val (h, _) = temps[i]
        val isSel = selected != null && selected >= h && selected < h + 3600
        val r = if (isSel) 5.dp.toPx() else 3.5.dp.toPx()
        drawCircle(surface, r + 2.dp.toPx(), p) // anneau de séparation
        drawCircle(color, r, p)
    }
    // Étiquettes directes sélectives : minimum et maximum seulement.
    val iMin = vals.indexOf(vals.min())
    val iMax = vals.indexOf(vals.max())
    label(measurer, Fmt.temp(vals[iMax]), pts[iMax].x + 6.dp.toPx(), pts[iMax].y - 9.dp.toPx(), ink, bold = true)
    if (iMin != iMax) label(measurer, Fmt.temp(vals[iMin]), pts[iMin].x + 6.dp.toPx(), pts[iMin].y + 9.dp.toPx(), ink, bold = true)
    drawLine(ink.copy(alpha = 0.5f), Offset(left, base), Offset(right, base), strokeWidth = 1.dp.toPx())
    timeAxis(measurer, ws, we, left, right, base, muted, grid, withLabels = true)
    crosshair(selected, ws, we, left, right, top, base, ink)
}

/** Étiquette de seuil au-dessus de sa ligne (jamais sur l'axe), sauf si la ligne touche le haut du graphique. */
private fun DrawScope.thresholdLabelY(lineY: Float, top: Float): Float =
    if (lineY - 8.dp.toPx() < top + 4.dp.toPx()) lineY + 8.dp.toPx() else lineY - 8.dp.toPx()

@Composable
private fun ValuesTable(day: DayReport, c: Criteria, ws: Double, stepSec: Double, temps: List<Pair<Double, Double>>) {
    val style = MaterialTheme.typography.bodySmall
    Column(Modifier.fillMaxWidth()) {
        Row { Text("Pas", Modifier.weight(1f), style = style, fontWeight = FontWeight.SemiBold); Text("Précip.", Modifier.weight(1f), style = style, fontWeight = FontWeight.SemiBold) }
        day.stepRainMm.forEachIndexed { i, mm ->
            val s = ws + i * stepSec
            val e = min(s + stepSec, c.windowEnd.toSecondOfDay().toDouble())
            Row {
                Text("${hhmm(s)}–${hhmm(e)}", Modifier.weight(1f), style = style)
                Text(
                    if (mm.isNaN()) "pas de mesure" else Fmt.mm(mm) + if (day.wetSteps.getOrNull(i) == true) " ✔" else "",
                    Modifier.weight(1f), style = style,
                )
            }
        }
        if (temps.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row { Text("Heure", Modifier.weight(1f), style = style, fontWeight = FontWeight.SemiBold); Text("T moyenne", Modifier.weight(1f), style = style, fontWeight = FontWeight.SemiBold) }
            temps.forEach { (h, v) ->
                Row {
                    Text("${hhmm(h)}–${hhmm(h + 3600)}", Modifier.weight(1f), style = style)
                    Text(Fmt.temp(v), Modifier.weight(1f), style = style)
                }
            }
        }
        Text(
            "✔ = pas compté comme précipitation (cumul ${Fmt.wetThresholdLong(c)}). Le pluviomètre publie une valeur toutes les ${DayClassifier.RAIN_STEP_SEC / 60} min.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
