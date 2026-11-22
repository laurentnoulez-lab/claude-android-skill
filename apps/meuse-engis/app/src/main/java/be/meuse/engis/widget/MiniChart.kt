package be.meuse.engis.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import be.meuse.engis.domain.DayBar
import kotlin.math.max

/**
 * Petit histogramme dessiné en bitmap pour le widget (les widgets ne peuvent pas dessiner librement) :
 * 8 barres (7 jours + aujourd'hui hachuré), ligne tiretée de référence, numéro du jour sous chaque barre.
 */
object MiniChart {

    fun render(
        bars: List<DayBar>,
        reference: Double?,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        dark: Boolean,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val barColor = if (dark) 0xFF3987E5.toInt() else 0xFF2A78D6.toInt()
        val ink = if (dark) 0xFFF0EFEC.toInt() else 0xFF0B0B0B.toInt()
        val muted = 0xFF898781.toInt()
        val grid = if (dark) 0xFF2C2C2A.toInt() else 0xFFE1E0D9.toInt()

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = 10f * density
            textAlign = Paint.Align.CENTER
        }
        val labelH = text.textSize * 1.3f
        val bottom = heightPx - labelH
        val top = 2f * density
        val plotH = bottom - top
        val slot = widthPx.toFloat() / max(bars.size, 1)
        val barW = minOf(18f * density, slot * 0.6f)
        val maxV = (bars.mapNotNull { it.mean } + listOfNotNull(reference)).maxOrNull()?.times(1.08) ?: 1.0
        fun y(v: Double) = (bottom - v / maxV * plotH).toFloat()

        c.drawLine(0f, bottom, widthPx.toFloat(), bottom, Paint().apply { color = grid; strokeWidth = density })

        reference?.let {
            val yr = y(it)
            c.drawLine(
                0f, yr, widthPx.toFloat(), yr,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ink
                    strokeWidth = 1.5f * density
                    pathEffect = DashPathEffect(floatArrayOf(5f * density, 3f * density), 0f)
                },
            )
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
        val r = 3f * density
        bars.forEachIndexed { i, b ->
            val cx = slot * i + slot / 2
            text.color = if (b.isToday) ink else muted
            text.isFakeBoldText = b.isToday
            c.drawText(b.date.dayOfMonth.toString(), cx, heightPx - 2f * density, text)
            val v = b.mean ?: return@forEachIndexed
            val rect = RectF(cx - barW / 2, y(v), cx + barW / 2, bottom)
            val path = Path().apply {
                addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
            }
            if (b.isToday) {
                c.drawPath(path, Paint(fill).apply { alpha = 60 })
                c.save()
                c.clipPath(path)
                val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor; strokeWidth = 1.2f * density }
                var x = rect.left - rect.height()
                while (x < rect.right) {
                    c.drawLine(x, rect.bottom, x + rect.height(), rect.top, hatch)
                    x += 5f * density
                }
                c.restore()
                c.drawPath(path, Paint(fill).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * density })
            } else {
                c.drawPath(path, fill)
            }
        }
        return bmp
    }
}
