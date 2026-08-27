package com.example.slideshowstudio.engine

/** A composition: where each photo of a scene sits on the canvas. */
data class LayoutTemplate(
    val id: String,
    val slots: List<NormRect>,
) {
    val count: Int get() = slots.size
}

/**
 * Catalog of compositions, indexed by the number of photos in the scene and by the orientation of
 * the canvas.
 *
 * Landscape and portrait have their own compositions: a portrait video is not a landscape video
 * turned sideways, so side-by-side layouts that read well in 16:9 become unusable slivers in 9:16,
 * and stacked layouts take over.
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
        val portrait = canvasAspect < 1f
        return when (count.coerceIn(1, 4)) {
            1 -> single()
            2 -> if (portrait) portraitTwo(gx, gy, mx, my) else landscapeTwo(gx, gy, mx, my)
            3 -> if (portrait) portraitThree(gx, gy, mx, my) else landscapeThree(gx, gy, mx, my)
            else -> if (portrait) portraitFour(gx, gy, mx, my) else landscapeFour(gx, gy, mx, my)
        }
    }

    /** Full bleed: a single photo always owns the whole frame. */
    private fun single(): List<LayoutTemplate> = listOf(
        LayoutTemplate("1-full", listOf(NormRect.Full)),
    )

    // ---------------------------------------------------------------- landscape 16:9

    private fun landscapeTwo(gx: Float, gy: Float, mx: Float, my: Float): List<LayoutTemplate> = listOf(
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
                NormRect(mx, my, 0.5f - gx / 2f, 1f - my - my),
                NormRect(0.5f + gx / 2f, my + my, 1f - mx, 1f - my),
            ),
        ),
    )

    private fun landscapeThree(gx: Float, gy: Float, mx: Float, my: Float): List<LayoutTemplate> {
        val bigLeft = columns(NormRect.Full, listOf(63f, 37f), gx)
        val bigRight = columns(NormRect.Full, listOf(37f, 63f), gx)
        val bigTop = rows(NormRect.Full, listOf(62f, 38f), gy)
        val bigBottom = rows(NormRect.Full, listOf(38f, 62f), gy)
        val insetSplit = columns(NormRect.Full.inset(mx, my), listOf(60f, 40f), gx)
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

    private fun landscapeFour(gx: Float, gy: Float, mx: Float, my: Float): List<LayoutTemplate> {
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

    // ---------------------------------------------------------------- portrait 9:16

    private fun portraitTwo(gx: Float, gy: Float, mx: Float, my: Float): List<LayoutTemplate> = listOf(
        LayoutTemplate("p2-stack-equal", rows(NormRect.Full, listOf(1f, 1f), gy)),
        LayoutTemplate("p2-stack-tall-top", rows(NormRect.Full, listOf(56f, 44f), gy)),
        LayoutTemplate("p2-stack-tall-bottom", rows(NormRect.Full, listOf(44f, 56f), gy)),
        LayoutTemplate("p2-stack-hero-top", rows(NormRect.Full, listOf(64f, 36f), gy)),
        LayoutTemplate("p2-stack-hero-bottom", rows(NormRect.Full, listOf(36f, 64f), gy)),
        // Two cards stacked, nudged sideways against each other.
        LayoutTemplate(
            "p2-stack-offset",
            listOf(
                NormRect(mx, my, 1f - mx - mx, 0.5f - gy / 2f),
                NormRect(mx + mx, 0.5f + gy / 2f, 1f - mx, 1f - my),
            ),
        ),
        // Side by side inside a band: only comfortable for two portrait photos.
        LayoutTemplate(
            "p2-side-band",
            columns(NormRect(0f, 0.20f, 1f, 0.80f), listOf(1f, 1f), gx),
        ),
    )

    private fun portraitThree(gx: Float, gy: Float, mx: Float, my: Float): List<LayoutTemplate> {
        val heroTop = rows(NormRect.Full, listOf(58f, 42f), gy)
        val heroBottom = rows(NormRect.Full, listOf(42f, 58f), gy)
        return listOf(
            LayoutTemplate("p3-rows", rows(NormRect.Full, listOf(1f, 1f, 1f), gy)),
            LayoutTemplate("p3-rows-hero-top", rows(NormRect.Full, listOf(48f, 26f, 26f), gy)),
            LayoutTemplate("p3-rows-hero-bottom", rows(NormRect.Full, listOf(26f, 26f, 48f), gy)),
            LayoutTemplate("p3-rows-hero-middle", rows(NormRect.Full, listOf(25f, 50f, 25f), gy)),
            LayoutTemplate("p3-hero-top-pair", listOf(heroTop[0]) + columns(heroTop[1], listOf(1f, 1f), gx)),
            LayoutTemplate("p3-hero-bottom-pair", listOf(heroBottom[1]) + columns(heroBottom[0], listOf(1f, 1f), gx)),
            LayoutTemplate(
                "p3-hero-top-pair-inset",
                (rows(NormRect.Full.inset(mx, my), listOf(56f, 44f), gy)).let { area ->
                    listOf(area[0]) + columns(area[1], listOf(46f, 54f), gx)
                },
            ),
        )
    }

    private fun portraitFour(gx: Float, gy: Float, mx: Float, my: Float): List<LayoutTemplate> {
        val gridRows = rows(NormRect.Full, listOf(1f, 1f), gy)
        val asymRows = rows(NormRect.Full, listOf(46f, 54f), gy)
        val mixedTop = rows(NormRect.Full, listOf(32f, 34f, 34f), gy)
        val mixedBottom = rows(NormRect.Full, listOf(34f, 34f, 32f), gy)
        val staggerRows = rows(NormRect.Full.inset(0f, my), listOf(1f, 1f, 1f, 1f), gy)
        val offsets = listOf(0.04f, 0.12f, 0.02f, 0.10f)
        val tileWidth = 0.86f
        return listOf(
            LayoutTemplate(
                "p4-grid",
                columns(gridRows[0], listOf(1f, 1f), gx) + columns(gridRows[1], listOf(1f, 1f), gx),
            ),
            LayoutTemplate(
                "p4-grid-asymmetric",
                columns(asymRows[0], listOf(55f, 45f), gx) + columns(asymRows[1], listOf(42f, 58f), gx),
            ),
            LayoutTemplate("p4-rows", rows(NormRect.Full, listOf(1f, 1f, 1f, 1f), gy)),
            // A pair on top, then two panoramic bands.
            LayoutTemplate(
                "p4-pair-top",
                columns(mixedTop[0], listOf(1f, 1f), gx) + listOf(mixedTop[1], mixedTop[2]),
            ),
            LayoutTemplate(
                "p4-pair-bottom",
                listOf(mixedBottom[0], mixedBottom[1]) + columns(mixedBottom[2], listOf(1f, 1f), gx),
            ),
            // Four bands nudged left and right: the "slightly offset" composition, vertical version.
            LayoutTemplate(
                "p4-rows-staggered",
                staggerRows.mapIndexed { index, row ->
                    NormRect(offsets[index], row.top, offsets[index] + tileWidth, row.bottom)
                },
            ),
        )
    }

    // ---------------------------------------------------------------- helpers

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
