package com.example.slideshowstudio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.roundToInt

/** Size of a photo once its EXIF orientation has been applied. */
data class PhotoSize(val width: Int, val height: Int)

/**
 * Decodes photos at the resolution the renderer actually needs.
 *
 * Two passes: `inSampleSize` gets close to the target with almost no allocation, then a single
 * matrix pass applies the exact scale and the EXIF rotation at once.
 */
object BitmapDecoder {

    private const val TAG = "BitmapDecoder"

    fun readSize(context: Context, uri: Uri): PhotoSize? {
        val bounds = decodeBounds(context, uri) ?: return null
        val orientation = readOrientation(context, uri)
        return if (orientation.swapsDimensions) {
            PhotoSize(bounds.height, bounds.width)
        } else {
            PhotoSize(bounds.width, bounds.height)
        }
    }

    /**
     * @param targetWidth width wanted for the decoded bitmap, after rotation. The result is never
     *   upscaled beyond the original resolution.
     */
    fun decode(context: Context, uri: Uri, targetWidth: Int): Bitmap? {
        val bounds = decodeBounds(context, uri) ?: return null
        val orientation = readOrientation(context, uri)
        val rotatedWidth = if (orientation.swapsDimensions) bounds.height else bounds.width
        if (rotatedWidth <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(rotatedWidth, targetWidth)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Impossible de décoder $uri", error)
            null
        } ?: return null

        val decodedRotatedWidth = if (orientation.swapsDimensions) decoded.height else decoded.width
        val scale = (targetWidth.toFloat() / decodedRotatedWidth).coerceAtMost(1f)
        if (orientation == Orientation.NORMAL && scale > 0.999f) return decoded

        val matrix = Matrix().apply {
            postScale(scale, scale)
            orientation.applyTo(this)
        }
        return try {
            val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (transformed != decoded) decoded.recycle()
            transformed
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "Mémoire insuffisante pour transformer $uri", error)
            decoded
        }
    }

    private fun decodeBounds(context: Context, uri: Uri): PhotoSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            if (options.outWidth > 0 && options.outHeight > 0) {
                PhotoSize(options.outWidth, options.outHeight)
            } else {
                null
            }
        } catch (error: Exception) {
            Log.w(TAG, "Impossible de lire les dimensions de $uri", error)
            null
        }
    }

    private fun readOrientation(context: Context, uri: Uri): Orientation = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            Orientation.fromExif(
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                ),
            )
        } ?: Orientation.NORMAL
    } catch (error: Exception) {
        Orientation.NORMAL
    }

    /** Largest power of two that keeps the decoded bitmap at or above [targetWidth]. */
    private fun sampleSizeFor(sourceWidth: Int, targetWidth: Int): Int {
        if (targetWidth <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= max(targetWidth, 1)) {
            sample *= 2
        }
        return sample
    }

    /** EXIF orientations, including the mirrored ones some phones produce with the front camera. */
    enum class Orientation(private val rotation: Float, private val mirrored: Boolean) {
        NORMAL(0f, false),
        ROTATE_90(90f, false),
        ROTATE_180(180f, false),
        ROTATE_270(270f, false),
        FLIP_HORIZONTAL(0f, true),
        FLIP_VERTICAL(180f, true),
        TRANSPOSE(90f, true),
        TRANSVERSE(270f, true);

        val swapsDimensions: Boolean get() = rotation == 90f || rotation == 270f

        fun applyTo(matrix: Matrix) {
            if (mirrored) matrix.postScale(-1f, 1f)
            if (rotation != 0f) matrix.postRotate(rotation)
        }

        companion object {
            fun fromExif(value: Int): Orientation = when (value) {
                ExifInterface.ORIENTATION_ROTATE_90 -> ROTATE_90
                ExifInterface.ORIENTATION_ROTATE_180 -> ROTATE_180
                ExifInterface.ORIENTATION_ROTATE_270 -> ROTATE_270
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> FLIP_HORIZONTAL
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> FLIP_VERTICAL
                ExifInterface.ORIENTATION_TRANSPOSE -> TRANSPOSE
                ExifInterface.ORIENTATION_TRANSVERSE -> TRANSVERSE
                else -> NORMAL
            }
        }
    }

    /** Rounds a dimension to an even number, which several encoders require. */
    fun roundToEven(value: Float): Int = (value.roundToInt() / 2) * 2

    /**
     * A heavily blurred, slightly desaturated copy of a photo, used as a scene backdrop.
     *
     * The blur is mostly free: the photo is decoded at a few dozen pixels wide, which throws away
     * every detail, and the renderer scales it back up with bilinear filtering. A short box blur on
     * top removes the last traces of blockiness, and pulling the colours towards grey keeps the
     * backdrop from competing with the photos in front of it.
     */
    fun decodeBackdrop(context: Context, uri: Uri, targetWidth: Int = BACKDROP_WIDTH): Bitmap? {
        val small = decode(context, uri, targetWidth) ?: return null
        return try {
            val width = small.width
            val height = small.height
            val pixels = IntArray(width * height)
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            boxBlur(pixels, width, height, BLUR_RADIUS)
            boxBlur(pixels, width, height, BLUR_RADIUS)
            desaturate(pixels, BACKDROP_SATURATION)
            small.setPixels(pixels, 0, width, 0, 0, width, height)
            small
        } catch (error: Exception) {
            Log.w(TAG, "Impossible de flouter $uri", error)
            small
        } catch (error: OutOfMemoryError) {
            small
        }
    }

    private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0 || width <= 1 || height <= 1) return
        val buffer = IntArray(pixels.size)
        blurRows(pixels, buffer, width, height, radius)
        blurColumns(buffer, pixels, width, height, radius)
    }

    private fun blurRows(source: IntArray, target: IntArray, width: Int, height: Int, radius: Int) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (offset in -radius..radius) {
                    val sampleX = (x + offset).coerceIn(0, width - 1)
                    val pixel = source[row + sampleX]
                    r += (pixel shr 16) and 0xFF
                    g += (pixel shr 8) and 0xFF
                    b += pixel and 0xFF
                    count++
                }
                target[row + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
    }

    private fun blurColumns(source: IntArray, target: IntArray, width: Int, height: Int, radius: Int) {
        for (x in 0 until width) {
            for (y in 0 until height) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (offset in -radius..radius) {
                    val sampleY = (y + offset).coerceIn(0, height - 1)
                    val pixel = source[sampleY * width + x]
                    r += (pixel shr 16) and 0xFF
                    g += (pixel shr 8) and 0xFF
                    b += pixel and 0xFF
                    count++
                }
                target[y * width + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
    }

    private fun desaturate(pixels: IntArray, keep: Float) {
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = (0.299f * r + 0.587f * g + 0.114f * b)
            fun mix(component: Int): Int =
                (component * keep + luma * (1f - keep)).roundToInt().coerceIn(0, 255)
            pixels[index] = (0xFF shl 24) or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
        }
    }

    private const val BACKDROP_WIDTH = 96
    private const val BLUR_RADIUS = 3
    private const val BACKDROP_SATURATION = 0.7f
}
