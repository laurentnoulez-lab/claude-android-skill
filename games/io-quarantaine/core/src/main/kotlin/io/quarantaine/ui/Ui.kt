package io.quarantaine.ui

import io.quarantaine.gfx.Font
import io.quarantaine.gfx.GlUtil
import io.quarantaine.gfx.HudBatch
import io.quarantaine.gfx.Img
import io.quarantaine.gfx.Program
import io.quarantaine.gfx.StreamBuffer
import io.quarantaine.platform.GL
import io.quarantaine.platform.Gl
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Régions de l'atlas d'icônes (en pixels, atlas 256x256). */
object Icons {
    const val SIZE = 256
    val CIRCLE = intArrayOf(0, 0, 64, 64)
    val RING = intArrayOf(64, 0, 128, 64)
    val WHITE = intArrayOf(130, 2, 134, 6)
    val VIGNETTE = intArrayOf(192, 0, 256, 64)
    val PAUSE = intArrayOf(0, 64, 32, 96)
    val MAP = intArrayOf(32, 64, 64, 96)
    val HEALTH = intArrayOf(64, 64, 96, 96)
    val ARMOR = intArrayOf(96, 64, 128, 96)
    val KEY = intArrayOf(128, 64, 160, 96)
    val ARROW = intArrayOf(160, 64, 192, 96)
    val CYCLE = intArrayOf(192, 64, 224, 96)
    val FIRE = intArrayOf(224, 64, 256, 96)
    val WEAPONS = arrayOf(
        intArrayOf(0, 96, 64, 128), intArrayOf(64, 96, 128, 128), intArrayOf(128, 96, 192, 128),
        intArrayOf(192, 96, 256, 128), intArrayOf(0, 128, 64, 160), intArrayOf(64, 128, 128, 160),
    )

    fun build(): Img {
        val im = Img(SIZE, SIZE)
        im.fill(1f, 1f, 1f, 0f)
        // Disque plein et anneau (bords lissés)
        im.disc(32f, 32f, 30f, 1f, 1f, 1f, 1f)
        for (y in 0 until 64) for (x in 64 until 128) {
            val d = sqrt((x + 0.5f - 96f) * (x + 0.5f - 96f) + (y + 0.5f - 32f) * (y + 0.5f - 32f))
            val a = (1f - kotlin.math.abs(d - 27.5f) / 3f).coerceIn(0f, 1f)
            im.set(x, y, 1f, 1f, 1f, a)
        }
        im.rect(128, 0, 136, 8, 1f, 1f, 1f, 1f)
        for (y in 0 until 64) for (x in 192 until 256) {
            val dx = (x + 0.5f - 224f) / 32f; val dy = (y + 0.5f - 32f) / 32f
            val d = sqrt(dx * dx + dy * dy)
            im.set(x, y, 1f, 1f, 1f, ((d - 0.45f) / 0.55f).coerceIn(0f, 1f).let { it * it })
        }
        // Pause
        im.rect(8, 70, 14, 90, 1f, 1f, 1f, 1f); im.rect(18, 70, 24, 90, 1f, 1f, 1f, 1f)
        // Carte : trois panneaux
        for (k in 0 until 3) im.rect(36 + k * 8, 70 + (k % 2) * 2, 43 + k * 8, 90 - ((k + 1) % 2) * 2, 1f, 1f, 1f, 1f)
        // Croix de soin
        im.rect(76, 68, 84, 92, 1f, 1f, 1f, 1f); im.rect(68, 76, 92, 84, 1f, 1f, 1f, 1f)
        // Bouclier
        for (y in 68 until 94) for (x in 98 until 126) {
            val yy = y - 68; val half = if (yy < 12) 13f else 13f - (yy - 12) * 1.1f
            if (kotlin.math.abs(x + 0.5f - 112f) < half) im.set(x, y, 1f, 1f, 1f, 1f)
        }
        // Carte d'accès
        im.rect(134, 72, 156, 88, 1f, 1f, 1f, 1f); im.rect(138, 76, 146, 80, 1f, 1f, 1f, 0.4f)
        // Flèche (automap)
        for (y in 66 until 94) for (x in 162 until 190) {
            val yy = (y - 66) / 28f; val half = yy * 13f
            if (kotlin.math.abs(x + 0.5f - 176f) < half && y < 92) im.set(x, y, 1f, 1f, 1f, 1f)
        }
        // Cycle d'armes : deux flèches
        for (a in 0 until 360 step 2) {
            if (a in 150..210 || a > 330 || a < 30) continue
            val r = a * 0.0174533f
            im.disc(208f + cos(r) * 10f, 80f + sin(r) * 10f, 2f, 1f, 1f, 1f, 1f)
        }
        for (i in 0 until 6) { im.rect(214 + i, 66 + i, 222 - i, 67 + i, 1f, 1f, 1f, 1f) }
        // Réticule « tir »
        im.disc(240f, 80f, 12f, 1f, 1f, 1f, 1f); im.disc(240f, 80f, 9f, 1f, 1f, 1f, 0f)
        im.disc(240f, 80f, 4f, 1f, 1f, 1f, 1f)
        im.rect(239, 66, 241, 72, 1f, 1f, 1f, 1f); im.rect(239, 88, 241, 94, 1f, 1f, 1f, 1f)
        im.rect(226, 79, 232, 81, 1f, 1f, 1f, 1f); im.rect(248, 79, 254, 81, 1f, 1f, 1f, 1f)
        // Silhouettes d'armes (64x32)
        weapon(im, 0, 96) { o, ox, oy -> o.rect(ox + 18, oy + 12, ox + 40, oy + 24, 1f, 1f, 1f, 1f); o.rect(ox + 40, oy + 14, ox + 46, oy + 22, 1f, 1f, 1f, 1f); o.rect(ox + 12, oy + 14, ox + 18, oy + 22, 1f, 1f, 1f, 1f) }
        weapon(im, 64, 96) { o, ox, oy -> o.rect(ox + 16, oy + 10, ox + 46, oy + 15, 1f, 1f, 1f, 1f); o.rect(ox + 18, oy + 15, ox + 25, oy + 26, 1f, 1f, 1f, 1f) }
        weapon(im, 128, 96) { o, ox, oy -> o.rect(ox + 8, oy + 11, ox + 58, oy + 15, 1f, 1f, 1f, 1f); o.rect(ox + 22, oy + 15, ox + 34, oy + 19, 1f, 1f, 1f, 1f); o.rect(ox + 4, oy + 12, ox + 16, oy + 22, 1f, 1f, 1f, 1f) }
        weapon(im, 192, 96) { o, ox, oy -> for (k in 0 until 3) o.rect(ox + 26, oy + 9 + k * 3, ox + 60, oy + 11 + k * 3, 1f, 1f, 1f, 1f); o.rect(ox + 6, oy + 8, ox + 28, oy + 20, 1f, 1f, 1f, 1f); o.rect(ox + 12, oy + 20, ox + 18, oy + 27, 1f, 1f, 1f, 1f) }
        weapon(im, 0, 128) { o, ox, oy -> o.rect(ox + 4, oy + 10, ox + 60, oy + 20, 1f, 1f, 1f, 1f); o.rect(ox + 22, oy + 20, ox + 28, oy + 28, 1f, 1f, 1f, 1f); o.rect(ox + 30, oy + 5, ox + 38, oy + 10, 1f, 1f, 1f, 1f) }
        weapon(im, 64, 128) { o, ox, oy -> o.rect(ox + 8, oy + 8, ox + 40, oy + 22, 1f, 1f, 1f, 1f); o.disc(ox + 24f, oy + 15f, 5f, 1f, 1f, 1f, 0.3f); for (k in 0 until 3) o.rect(ox + 40, oy + 9 + k * 5, ox + 58 - k * 2, oy + 11 + k * 5, 1f, 1f, 1f, 1f) }
        return im
    }

    private fun weapon(im: Img, ox: Int, oy: Int, f: (Img, Int, Int) -> Unit) = f(im, ox, oy)
}

/** Boîte à outils de dessin 2D (texte, formes, icônes) au-dessus de la scène. */
class Ui(private val gl: Gl, private val prog: Program, stream: StreamBuffer) {
    val batch = HudBatch(gl, stream)
    private var fontTex = 0
    private var iconTex = 0
    var w = 1f
    var h = 1f

    fun init() {
        fontTex = GlUtil.upload(gl, Font.buildAtlas(), pixelated = true, repeat = false, mipmaps = false)
        iconTex = GlUtil.upload(gl, Icons.build(), pixelated = false, repeat = false, mipmaps = false)
    }

    fun begin(width: Int, height: Int) {
        w = width.toFloat(); h = height.toFloat()
        gl.viewport(0, 0, width, height)
        gl.disable(GL.DEPTH_TEST)
        gl.disable(GL.CULL_FACE)
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.SRC_ALPHA, GL.ONE_MINUS_SRC_ALPHA)
        prog.use()
        gl.uniform2f(prog.u("u_screen"), w, h)
        gl.uniform1i(prog.u("u_tex"), 0)
        gl.enableVertexAttribArray(0); gl.enableVertexAttribArray(1); gl.enableVertexAttribArray(2)
        batch.begin()
    }

    fun end() {
        batch.flush()
        gl.disable(GL.BLEND)
        gl.enable(GL.DEPTH_TEST)
    }

    /** Taille d'un pixel de police pour une taille de texte relative à la hauteur d'écran. */
    fun px(rel: Float) = maxOf(1f, kotlin.math.round(h * rel))

    private fun icon(r: IntArray, x0: Float, y0: Float, x1: Float, y1: Float, cr: Float, cg: Float, cb: Float, a: Float) {
        batch.texture(iconTex)
        val s = Icons.SIZE.toFloat()
        batch.rect(x0, y0, x1, y1, r[0] / s, r[1] / s, r[2] / s, r[3] / s, cr, cg, cb, a)
    }

    fun rect(x0: Float, y0: Float, x1: Float, y1: Float, r: Float, g: Float, b: Float, a: Float) {
        batch.texture(iconTex)
        val s = Icons.SIZE.toFloat()
        val wr = Icons.WHITE
        batch.rect(x0, y0, x1, y1, (wr[0] + 1) / s, (wr[1] + 1) / s, (wr[2] - 1) / s, (wr[3] - 1) / s, r, g, b, a)
    }

    fun frame(x0: Float, y0: Float, x1: Float, y1: Float, t: Float, r: Float, g: Float, b: Float, a: Float) {
        rect(x0, y0, x1, y0 + t, r, g, b, a); rect(x0, y1 - t, x1, y1, r, g, b, a)
        rect(x0, y0 + t, x0 + t, y1 - t, r, g, b, a); rect(x1 - t, y0 + t, x1, y1 - t, r, g, b, a)
    }

    fun circle(cx: Float, cy: Float, rad: Float, r: Float, g: Float, b: Float, a: Float) = icon(Icons.CIRCLE, cx - rad, cy - rad, cx + rad, cy + rad, r, g, b, a)
    fun ring(cx: Float, cy: Float, rad: Float, r: Float, g: Float, b: Float, a: Float) = icon(Icons.RING, cx - rad, cy - rad, cx + rad, cy + rad, r, g, b, a)
    fun iconAt(reg: IntArray, cx: Float, cy: Float, size: Float, r: Float, g: Float, b: Float, a: Float) {
        val aspect = (reg[2] - reg[0]).toFloat() / (reg[3] - reg[1])
        icon(reg, cx - size * aspect / 2, cy - size / 2, cx + size * aspect / 2, cy + size / 2, r, g, b, a)
    }

    fun vignette(r: Float, g: Float, b: Float, a: Float) = icon(Icons.VIGNETTE, 0f, 0f, w, h, r, g, b, a)

    /** Flèche orientée (automap, indicateur de dégâts). [angle] : 0 = haut de l'écran, sens horaire. */
    fun arrow(cx: Float, cy: Float, size: Float, angle: Float, r: Float, g: Float, b: Float, a: Float) {
        batch.texture(iconTex)
        val s = Icons.SIZE.toFloat(); val reg = Icons.ARROW
        val c = cos(angle); val sn = sin(angle)
        fun px(x: Float, y: Float) = cx + x * c - y * sn
        fun py(x: Float, y: Float) = cy + x * sn + y * c
        val hs = size / 2
        batch.quad(px(-hs, -hs), py(-hs, -hs), px(hs, -hs), py(hs, -hs), px(hs, hs), py(hs, hs), px(-hs, hs), py(-hs, hs),
            reg[0] / s, reg[1] / s, reg[2] / s, reg[3] / s, r, g, b, a)
    }

    /** Texte en police bitmap. [align] : -1 gauche, 0 centré, 1 droite. Renvoie la largeur. */
    fun text(s: String, x: Float, y: Float, size: Float, r: Float, g: Float, b: Float, a: Float = 1f, align: Int = -1, shadow: Boolean = true): Float {
        val width = Font.width(s, size)
        val x0 = when (align) { 0 -> x - width / 2; 1 -> x - width; else -> x }
        if (shadow) drawText(s, x0 + size * 0.6f, y + size * 0.6f, size, 0f, 0f, 0f, a * 0.75f)
        drawText(s, x0, y, size, r, g, b, a)
        return width
    }

    private fun drawText(s: String, x0: Float, y: Float, size: Float, r: Float, g: Float, b: Float, a: Float) {
        batch.texture(fontTex)
        val at = Font.ATLAS.toFloat()
        var x = x0
        for (ch in s) {
            if (ch != ' ') {
                val gi = Font.glyphIndex(ch)
                val gx = Font.glyphX(gi); val gy = Font.glyphY(gi)
                batch.rect(x, y, x + Font.GLYPH_W * size, y + Font.GLYPH_H * size,
                    gx / at, gy / at, (gx + Font.GLYPH_W) / at, (gy + Font.GLYPH_H) / at, r, g, b, a)
            }
            x += Font.ADVANCE * size
        }
    }

    fun textWidth(s: String, size: Float) = Font.width(s, size)
}
