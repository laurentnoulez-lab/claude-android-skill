package com.example.slideshowstudio.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import com.example.slideshowstudio.engine.DrawCommand
import com.example.slideshowstudio.engine.Frame
import com.example.slideshowstudio.engine.NormRect
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws an engine [Frame] on a Compose canvas. Used by the on-device preview.
 *
 * The photo is drawn whole and clipped to the quad it occupies, with the crop applied through the
 * canvas transform rather than through integer source rectangles: sub-pixel precision is what keeps
 * a slow Ken Burns movement from stepping.
 *
 * @param image    the photo to draw for a given index.
 * @param backdrop the blurred, desaturated copy used behind the photos.
 */
fun DrawScope.drawSlideshowFrame(
    frame: Frame,
    image: (photoIndex: Int) -> ImageBitmap?,
    backdrop: (photoIndex: Int) -> ImageBitmap?,
) {
    drawRect(color = Color(frame.backgroundColor))

    frame.backdrops.forEach { command ->
        val bitmap = backdrop(command.photoIndex) ?: return@forEach
        drawQuad(
            bitmap = bitmap,
            src = command.src,
            dst = command.dst,
            clip = null,
            rotationDeg = 0f,
            alpha = command.alpha,
        )
    }
    if (frame.backdropDim > 0f) {
        drawRect(color = Color.Black, alpha = frame.backdropDim.coerceIn(0f, 1f))
    }

    frame.commands.forEach { command ->
        val bitmap = image(command.photoIndex) ?: return@forEach
        drawCommand(command, bitmap)
    }

    if (frame.blackout > 0f) {
        drawRect(color = Color.Black, alpha = frame.blackout.coerceIn(0f, 1f))
    }
}

private fun DrawScope.drawCommand(command: DrawCommand, bitmap: ImageBitmap) {
    drawQuad(
        bitmap = bitmap,
        src = command.src,
        dst = command.dst,
        clip = command.clip,
        rotationDeg = command.rotationDeg,
        alpha = command.alpha,
    )
}

private fun DrawScope.drawQuad(
    bitmap: ImageBitmap,
    src: NormRect,
    dst: NormRect,
    clip: NormRect?,
    rotationDeg: Float,
    alpha: Float,
) {
    val canvasWidth = size.width
    val canvasHeight = size.height
    val sourceWidthPx = src.width * bitmap.width
    if (sourceWidthPx <= 0f) return
    val scale = (dst.width * canvasWidth) / sourceWidthPx

    // Only the visible crop may reach the canvas, so the quad itself is the clip. The optional
    // rectangle on top of it trims the overshoot a rotated photo needs to keep its slot filled.
    val quad = quadPath(dst, rotationDeg, canvasWidth, canvasHeight)
    val paint: DrawScope.() -> Unit = {
        clipPath(quad) {
            withTransform({
                translate(dst.centerX * canvasWidth, dst.centerY * canvasHeight)
                rotate(rotationDeg, pivot = Offset.Zero)
                scale(scale, scale, pivot = Offset.Zero)
                translate(-src.centerX * bitmap.width, -src.centerY * bitmap.height)
            }) {
                drawImage(image = bitmap, topLeft = Offset.Zero, alpha = alpha)
            }
        }
    }

    if (clip == null) {
        paint()
    } else {
        clipRect(
            left = clip.left * canvasWidth,
            top = clip.top * canvasHeight,
            right = clip.right * canvasWidth,
            bottom = clip.bottom * canvasHeight,
        ) {
            paint()
        }
    }
}

/** The four corners of [rect], rotated around its center in pixel space. */
private fun quadPath(rect: NormRect, rotationDeg: Float, canvasWidth: Float, canvasHeight: Float): Path {
    val centerX = rect.centerX * canvasWidth
    val centerY = rect.centerY * canvasHeight
    val halfWidth = rect.width * canvasWidth / 2f
    val halfHeight = rect.height * canvasHeight / 2f
    val radians = Math.toRadians(rotationDeg.toDouble())
    val cosine = cos(radians).toFloat()
    val sine = sin(radians).toFloat()
    val corners = listOf(
        -halfWidth to -halfHeight,
        halfWidth to -halfHeight,
        halfWidth to halfHeight,
        -halfWidth to halfHeight,
    )
    return Path().apply {
        corners.forEachIndexed { index, (localX, localY) ->
            val x = centerX + localX * cosine - localY * sine
            val y = centerY + localX * sine + localY * cosine
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}
