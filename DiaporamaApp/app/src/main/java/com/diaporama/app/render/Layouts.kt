package com.diaporama.app.render

import android.graphics.RectF

/**
 * Computes the cell rectangles for a collage of [count] photos inside a frame
 * of [width] x [height], with an outer [margin] and inner [gap] (in pixels).
 *
 * Several distinct arrangements exist for each photo count; [variant] selects
 * one (it is taken modulo the number available), so successive screens can
 * alternate layouts randomly. Photos are drawn fully contained inside their
 * cell, so even thin or wide cells never crop the image.
 */
object Layouts {

    fun forCount(
        count: Int,
        width: Int,
        height: Int,
        margin: Float,
        gap: Float,
        variant: Int,
    ): List<RectF> {
        val n = count.coerceIn(1, 6)
        val left = margin
        val top = margin
        val w = width - 2 * margin
        val h = height - 2 * margin

        val variants: List<() -> List<RectF>> = when (n) {
            1 -> listOf(
                { listOf(RectF(left, top, left + w, top + h)) },
            )

            2 -> listOf(
                { columns(2, left, top, w, h, gap) },
                { rows(2, left, top, w, h, gap) },
            )

            3 -> listOf(
                { bigSideStack(2, 0.58f, left, top, w, h, gap, bigOnLeft = true) },
                { bigSideStack(2, 0.58f, left, top, w, h, gap, bigOnLeft = false) },
                { columns(3, left, top, w, h, gap) },
                { bigTopRowBottom(2, 0.56f, left, top, w, h, gap) },
            )

            4 -> listOf(
                { grid(2, 2, left, top, w, h, gap) },
                { columns(4, left, top, w, h, gap) },
                { bigSideStack(3, 0.5f, left, top, w, h, gap, bigOnLeft = true) },
                { topRowBottomRow(1, 3, left, top, w, h, gap) },
            )

            5 -> listOf(
                { topRowBottomRow(2, 3, left, top, w, h, gap) },
                { topRowBottomRow(3, 2, left, top, w, h, gap) },
                { bigSideGrid(2, 2, 0.5f, left, top, w, h, gap) },
            )

            else -> listOf(
                { grid(3, 2, left, top, w, h, gap) },
                { grid(2, 3, left, top, w, h, gap) },
            )
        }

        val idx = ((variant % variants.size) + variants.size) % variants.size
        return variants[idx]()
    }

    /** Number of arrangements available for a given photo count. */
    fun variantCount(count: Int): Int = when (count.coerceIn(1, 6)) {
        1 -> 1
        2 -> 2
        3 -> 4
        4 -> 4
        5 -> 3
        else -> 2
    }

    private fun columns(n: Int, left: Float, top: Float, w: Float, h: Float, gap: Float): List<RectF> {
        val cw = (w - (n - 1) * gap) / n
        return (0 until n).map { i ->
            val x = left + i * (cw + gap)
            RectF(x, top, x + cw, top + h)
        }
    }

    private fun rows(n: Int, left: Float, top: Float, w: Float, h: Float, gap: Float): List<RectF> {
        val ch = (h - (n - 1) * gap) / n
        return (0 until n).map { i ->
            val y = top + i * (ch + gap)
            RectF(left, y, left + w, y + ch)
        }
    }

    private fun grid(cols: Int, rows: Int, left: Float, top: Float, w: Float, h: Float, gap: Float): List<RectF> {
        val cw = (w - (cols - 1) * gap) / cols
        val ch = (h - (rows - 1) * gap) / rows
        return buildList {
            for (r in 0 until rows) {
                val y = top + r * (ch + gap)
                for (c in 0 until cols) {
                    val x = left + c * (cw + gap)
                    add(RectF(x, y, x + cw, y + ch))
                }
            }
        }
    }

    /** One large cell on one side, [k] stacked cells on the other. */
    private fun bigSideStack(
        k: Int,
        bigFrac: Float,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        gap: Float,
        bigOnLeft: Boolean,
    ): List<RectF> {
        val bigW = w * bigFrac
        val smallW = w - bigW - gap
        val smallH = (h - (k - 1) * gap) / k
        val bigX = if (bigOnLeft) left else left + smallW + gap
        val smallX = if (bigOnLeft) left + bigW + gap else left

        val result = ArrayList<RectF>(k + 1)
        result.add(RectF(bigX, top, bigX + bigW, top + h))
        for (i in 0 until k) {
            val y = top + i * (smallH + gap)
            result.add(RectF(smallX, y, smallX + smallW, y + smallH))
        }
        return result
    }

    /** One wide cell on top, a row of [k] cells underneath. */
    private fun bigTopRowBottom(
        k: Int,
        topFrac: Float,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        gap: Float,
    ): List<RectF> {
        val topH = h * topFrac
        val botH = h - topH - gap
        val botY = top + topH + gap
        val cw = (w - (k - 1) * gap) / k
        return buildList {
            add(RectF(left, top, left + w, top + topH))
            for (i in 0 until k) {
                val x = left + i * (cw + gap)
                add(RectF(x, botY, x + cw, botY + botH))
            }
        }
    }

    /** A row of [topN] cells over a row of [botN] cells. */
    private fun topRowBottomRow(
        topN: Int,
        botN: Int,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        gap: Float,
    ): List<RectF> {
        val ch = (h - gap) / 2f
        val botY = top + ch + gap
        return buildList {
            val topW = (w - (topN - 1) * gap) / topN
            for (i in 0 until topN) {
                val x = left + i * (topW + gap)
                add(RectF(x, top, x + topW, top + ch))
            }
            val botW = (w - (botN - 1) * gap) / botN
            for (i in 0 until botN) {
                val x = left + i * (botW + gap)
                add(RectF(x, botY, x + botW, botY + ch))
            }
        }
    }

    /** One large cell on the left, a [cols] x [rows] grid on the right. */
    private fun bigSideGrid(
        cols: Int,
        rows: Int,
        bigFrac: Float,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        gap: Float,
    ): List<RectF> {
        val bigW = w * bigFrac
        val gridW = w - bigW - gap
        val gridLeft = left + bigW + gap
        val cw = (gridW - (cols - 1) * gap) / cols
        val ch = (h - (rows - 1) * gap) / rows
        return buildList {
            add(RectF(left, top, left + bigW, top + h))
            for (r in 0 until rows) {
                val y = top + r * (ch + gap)
                for (c in 0 until cols) {
                    val x = gridLeft + c * (cw + gap)
                    add(RectF(x, y, x + cw, y + ch))
                }
            }
        }
    }
}
