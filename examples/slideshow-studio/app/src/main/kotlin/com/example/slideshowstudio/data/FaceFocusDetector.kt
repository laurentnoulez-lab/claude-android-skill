package com.example.slideshowstudio.data

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import android.util.Log
import com.example.slideshowstudio.engine.FocusArea
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the area of a photo that must survive cropping.
 *
 * Uses the face detector built into the platform: no dependency, no model download, and it runs
 * offline in a few milliseconds on a downscaled copy. It only finds reasonably frontal faces, which
 * is enough to stop the cropper from cutting heads; when it finds nothing the engine falls back to
 * its default focus.
 *
 * Swapping in ML Kit face detection later only means returning a different [FocusArea] here.
 */
object FaceFocusDetector {

    private const val TAG = "FaceFocusDetector"
    private const val MAX_FACES = 6
    private const val ANALYSIS_WIDTH = 320
    private const val MIN_CONFIDENCE = 0.35f

    fun detect(bitmap: Bitmap): FocusArea? {
        val analysis = downscaleTo565(bitmap) ?: return null
        return try {
            val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val found = FaceDetector(analysis.width, analysis.height, MAX_FACES).findFaces(analysis, faces)
            if (found <= 0) return null

            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            var kept = 0
            val midPoint = PointF()
            for (index in 0 until found) {
                val face = faces[index] ?: continue
                if (face.confidence() < MIN_CONFIDENCE) continue
                face.getMidPoint(midPoint)
                val eyes = face.eyesDistance()
                left = min(left, midPoint.x - eyes * 1.4f)
                right = max(right, midPoint.x + eyes * 1.4f)
                // Heads reach higher above the eye line than below the chin.
                top = min(top, midPoint.y - eyes * 1.8f)
                bottom = max(bottom, midPoint.y + eyes * 2.0f)
                kept++
            }
            if (kept == 0) return null

            FocusArea(
                left = (left / analysis.width).coerceIn(0f, 1f),
                top = (top / analysis.height).coerceIn(0f, 1f),
                right = (right / analysis.width).coerceIn(0f, 1f),
                bottom = (bottom / analysis.height).coerceIn(0f, 1f),
            )
        } catch (error: Exception) {
            Log.w(TAG, "Détection de visages indisponible", error)
            null
        } finally {
            if (analysis != bitmap) analysis.recycle()
        }
    }

    /** [FaceDetector] only accepts RGB_565 bitmaps whose width is even. */
    private fun downscaleTo565(bitmap: Bitmap): Bitmap? = try {
        val scale = min(1f, ANALYSIS_WIDTH.toFloat() / max(bitmap.width, 1))
        val width = ((bitmap.width * scale).toInt() / 2) * 2
        val height = (bitmap.height * scale).toInt()
        if (width < 32 || height < 32) {
            null
        } else {
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val rgb565 = scaled.copy(Bitmap.Config.RGB_565, false)
            if (scaled != bitmap && scaled != rgb565) scaled.recycle()
            rgb565
        }
    } catch (error: Exception) {
        Log.w(TAG, "Impossible de préparer l'image pour la détection", error)
        null
    } catch (error: OutOfMemoryError) {
        null
    }
}
