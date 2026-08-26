package com.example.slideshowstudio.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import com.example.slideshowstudio.engine.DrawCommand
import com.example.slideshowstudio.engine.Frame
import com.example.slideshowstudio.engine.NormRect

/**
 * Draws an engine [Frame] on a Compose canvas. Used by the on-device preview.
 *
 * The photo is drawn whole and clipped to its slot, with the crop applied through the canvas
 * transform rather than through integer source rectangles: sub-pixel precision is what keeps a slow
 * Ken Burns movement from stepping.
 */
fun DrawScope.drawSlideshowFrame(
    frame: Frame,
    background: Color,
    image: (photoIndex: Int) -> ImageBitmap?,
) {
    drawRect(color = background)
    frame.commands.forEach { command ->
        val bitmap = image(command.photoIndex) ?: return@forEach
        drawCommand(command, bitmap)
    }
    if (frame.blackout > 0f) {
        drawRect(color = Color.Black, alpha = frame.blackout.coerceIn(0f, 1f))
    }
}

private fun DrawScope.drawCommand(command: DrawCommand, bitmap: ImageBitmap) {
    val canvasWidth = size.width
    val canvasHeight = size.height
    val clip = command.clip ?: NormRect.Full

    val sourceWidthPx = command.src.width * bitmap.width
    if (sourceWidthPx <= 0f) return
    val scale = (command.dst.width * canvasWidth) / sourceWidthPx

    clipRect(
        left = clip.left * canvasWidth,
        top = clip.top * canvasHeight,
        right = clip.right * canvasWidth,
        bottom = clip.bottom * canvasHeight,
    ) {
        withTransform({
            translate(command.dst.centerX * canvasWidth, command.dst.centerY * canvasHeight)
            rotate(command.rotationDeg, pivot = Offset.Zero)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-command.src.centerX * bitmap.width, -command.src.centerY * bitmap.height)
        }) {
            drawImage(image = bitmap, topLeft = Offset.Zero, alpha = command.alpha)
        }
    }
}
