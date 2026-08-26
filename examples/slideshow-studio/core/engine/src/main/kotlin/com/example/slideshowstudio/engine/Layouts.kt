package com.example.slideshowstudio.engine

/** A composition: where each photo of a scene sits on the canvas. */
data class LayoutTemplate(
    val id: String,
    val slots: List<NormRect>,
) {
    val count: Int get() = slots.size
}

/**
 * Catalog of compositions, indexed by the number of photos in the scene.
 *
 * Gaps are expressed so that they measure the same number of pixels horizontally and vertically,
 * whatever the canvas aspect ratio.
 */
object LayoutCatalog {

    private const val GAP_X = 0.0075f
    private const val MARGIN_X = 0.028f

    fun templatesFor(count: Int, canvasAspect: Float): List<LayoutTemplate> {
        val gx = GAP_X
        val gy = GAP_X * canvasAspect
        val mx = MARGIN_X
        val my = MARGIN_X * canvasAspect
        return when (count.coerceIn(1, 4)) {
            1 -> single()
            2 -> two(gx, gy, mx, my)
            3 -> three(gx, gy, mx, my)
            else -> four(gx, gy, mx, my)
        }
    }

    /** Full bleed: a single photo always owns the whole frame. */
    private fun single(): List<LayoutTemplate> = listOf(
        LayoutTemplate("1-full", listOf(NormRect.Full)),
    )

    private fun two(gx: Float, gy: Float, mx: Float, my: Float): List<LayoutTemplate> = listOf(
        LayoutTemplate("2-side-equal", columns(NormRect.Full, listOf(1f, 1f), gx)),
        LayoutTemplate("2-side-wide-left", columns(NormRect.Full, listOf(58f, 42f), gx)),
        LayoutTemplate("2-side-wide-right", columns(NormRect.Full, listOf(42f, 58f), gx)),
        LayoutTemplate("2-stack-equal", rows(NormRect.Full, listOf(1f, 1f), gy)),
        LayoutTemplate("2-stack-tall-top", rows(NormRect.Full, listOf(56f, 44f), gy)),
        LayoutTemplate("2-stack-tall-bottom", rows(NormRect.Full, listOf(44f, 56f), gy)),
        // Two cards of the same size, shifted vertically against each other.
        LayoutTemplate(
            "2-side-offset",
            listOf(
                NormRect(mx, my, 0.5f - gx / 2f, 1f - my - 0.06f * 16f / 9f),
                NormRect(0.5f + gx / 2f, my + 0.06f * 16f / 9f, 1f - mx, 1f - my),
            ),
        ),
    )

    private fun three(gx: Float, gy: Float, mx: Float, my: Float): List<LayoutTemplate> {
        val bigLeft = columns(NormRect.Full, listOf(63f, 37f), gx)
        val bigRight = columns(NormRect.Full, listOf(37f, 63f), gx)
        val bigTop = rows(NormRect.Full, listOf(62f, 38f), gy)
        val bigBottom = rows(NormRect.Full, listOf(38f, 62f), gy)
        val insetArea = NormRect.Full.inset(mx, my)
        val insetSplit = columns(insetArea, listOf(60f, 40f), gx)
        return listOf(
            LayoutTemplate("3-hero-left", listOf(bigLeft[0]) + rows(bigLeft[1], listOf(1f, 1f), gy)),
            LayoutTemplate("3-hero-right", listOf(bigRight[1]) + rows(bigRight[0], listOf(1f, 1f), gy)),
            LayoutTemplate("3-hero-top", listOf(bigTop[0]) + columns(bigTop[1], listOf(1f, 1f), gx)),
            LayoutTemplate("3-hero-bottom", listOf(bigBottom[1]) + columns(bigBottom[0], listOf(1f, 1f), gx)),
            LayoutTemplate("3-columns", columns(NormRect.Full, listOf(32f, 38f, 30f), gx)),
            LayoutTemplate("3-columns-wide-center", columns(NormRect.Full, listOf(28f, 44f, 28f), gx)),
            LayoutTemplate("3-hero-left-inset", listOf(insetSplit[0]) + rows(insetSplit[1], listOf(52f, 48f), gy)),
        )
    }

    private fun four(gx: Float, gy: Float, mx: Float, my: Float): List<LayoutTemplate> {
        val gridRows = rows(NormRect.Full, listOf(1f, 1f), gy)
        val asymRows = rows(NormRect.Full, listOf(46f, 54f), gy)
        val mixedRows = rows(NormRect.Full, listOf(53f, 47f), gy)
        val heroLeft = columns(NormRect.Full, listOf(56f, 44f), gx)
        val heroRight = columns(NormRect.Full, listOf(44f, 56f), gx)
        val staggerCols = columns(NormRect.Full.inset(mx, 0f), listOf(26f, 24f, 26f, 24f), gx)
        val offsets = listOf(0.05f, 0.15f, 0.03f, 0.13f)
        val tileHeight = 0.80f
        return listOf(
            LayoutTemplate(
                "4-grid",
                columns(gridRows[0], listOf(1f, 1f), gx) + columns(gridRows[1], listOf(1f, 1f), gx),
            ),
            LayoutTemplate(
                "4-grid-asymmetric",
                columns(asymRows[0], listOf(55f, 45f), gx) + columns(asymRows[1], listOf(42f, 58f), gx),
            ),
            LayoutTemplate(
                "4-rows-mixed",
                columns(mixedRows[0], listOf(62f, 38f), gx) + columns(mixedRows[1], listOf(45f, 55f), gx),
            ),
            LayoutTemplate("4-hero-left", listOf(heroLeft[0]) + rows(heroLeft[1], listOf(1f, 1f, 1f), gy)),
            LayoutTemplate("4-hero-right", listOf(heroRight[1]) + rows(heroRight[0], listOf(1f, 1f, 1f), gy)),
            // Four columns nudged up and down: the "slightly offset" composition.
            LayoutTemplate(
                "4-columns-staggered",
                staggerCols.mapIndexed { index, column ->
                    NormRect(column.left, offsets[index], column.right, offsets[index] + tileHeight)
                },
            ),
        )
    }

    /** Splits [area] into vertical strips using [weights], separated by [gap]. */
    fun columns(area: NormRect, weights: List<Float>, gap: Float): List<NormRect> {
        val total = weights.sum()
        val available = area.width - gap * (weights.size - 1)
        var x = area.left
        return weights.map { weight ->
            val w = available * (weight / total)
            val rect = NormRect(x, area.top, x + w, area.bottom)
            x += w + gap
            rect
        }
    }

    /** Splits [area] into horizontal bands using [weights], separated by [gap]. */
    fun rows(area: NormRect, weights: List<Float>, gap: Float): List<NormRect> {
        val total = weights.sum()
        val available = area.height - gap * (weights.size - 1)
        var y = area.top
        return weights.map { weight ->
            val h = available * (weight / total)
            val rect = NormRect(area.left, y, area.right, y + h)
            y += h + gap
            rect
        }
    }
}
