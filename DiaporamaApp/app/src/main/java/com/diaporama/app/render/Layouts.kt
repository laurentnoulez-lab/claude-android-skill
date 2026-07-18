package com.diaporama.app.render

import android.graphics.RectF

/**
 * Computes the cell rectangles for a collage of [count] photos inside a frame
 * of [width] x [height], with an outer [margin] and inner [gap] (in pixels).
 * Layouts are hand-tuned so that 1..6 photos each fill the frame pleasantly.
 */
object Layouts {

    fun forCount(count: Int, width: Int, height: Int, margin: Float, gap: Float): List<RectF> {
        val n = count.coerceIn(1, 6)
        val left = margin
        val top = margin
        val right = width - margin
        val bottom = height - margin
        val w = right - left
        val h = bottom - top

        return when (n) {
            1 -> listOf(RectF(left, top, right, bottom))

            2 -> {
                val cw = (w - gap) / 2f
                listOf(
                    RectF(left, top, left + cw, bottom),
                    RectF(left + cw + gap, top, right, bottom),
                )
            }

            3 -> {
                // One tall photo on the left, two stacked on the right.
                val bigW = w * 0.58f
                val smallW = w - bigW - gap
                val smallH = (h - gap) / 2f
                val rx = left + bigW + gap
                listOf(
                    RectF(left, top, left + bigW, bottom),
                    RectF(rx, top, rx + smallW, top + smallH),
                    RectF(rx, top + smallH + gap, rx + smallW, bottom),
                )
            }

            4 -> {
                val cw = (w - gap) / 2f
                val ch = (h - gap) / 2f
                listOf(
                    RectF(left, top, left + cw, top + ch),
                    RectF(left + cw + gap, top, right, top + ch),
                    RectF(left, top + ch + gap, left + cw, bottom),
                    RectF(left + cw + gap, top + ch + gap, right, bottom),
                )
            }

            5 -> {
                // Two on top, three on the bottom.
                val ch = (h - gap) / 2f
                val topW = (w - gap) / 2f
                val botW = (w - 2 * gap) / 3f
                val by = top + ch + gap
                listOf(
                    RectF(left, top, left + topW, top + ch),
                    RectF(left + topW + gap, top, right, top + ch),
                    RectF(left, by, left + botW, bottom),
                    RectF(left + botW + gap, by, left + 2 * botW + gap, bottom),
                    RectF(left + 2 * (botW + gap), by, right, bottom),
                )
            }

            else -> {
                // 6: 3 columns x 2 rows.
                val cw = (w - 2 * gap) / 3f
                val ch = (h - gap) / 2f
                val row2 = top + ch + gap
                buildList {
                    for (row in 0..1) {
                        val ty = if (row == 0) top else row2
                        for (col in 0..2) {
                            val cx = left + col * (cw + gap)
                            add(RectF(cx, ty, cx + cw, ty + ch))
                        }
                    }
                }
            }
        }
    }
}
