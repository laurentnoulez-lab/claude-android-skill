package com.example.slideshowstudio.render.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import com.example.slideshowstudio.engine.DrawCommand
import com.example.slideshowstudio.engine.NormRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws engine frames with OpenGL ES 2.0, used to render the exported video.
 *
 * Each photo is one textured quad. Corners are computed on the CPU (there are at most eight quads
 * per frame) which keeps the shader trivial and puts the rotation in pixel space, exactly like the
 * Compose preview does — so the preview and the exported file match.
 */
class GlFrameRenderer(
    private val width: Int,
    private val height: Int,
    private val backgroundColor: FloatArray = floatArrayOf(0.055f, 0.055f, 0.07f, 1f),
) {

    private var textureProgram = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexture = 0
    private var uAlpha = 0

    private var solidProgram = 0
    private var aSolidPosition = 0
    private var uSolidColor = 0

    private val vertexBuffer: FloatBuffer = allocateFloatBuffer(VERTEX_COUNT * FLOATS_PER_VERTEX)
    private val quadBuffer: FloatBuffer = allocateFloatBuffer(VERTEX_COUNT * 2)

    fun setUp() {
        textureProgram = buildProgram(TEXTURE_VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(textureProgram, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(textureProgram, "aTexCoord")
        uTexture = GLES20.glGetUniformLocation(textureProgram, "uTexture")
        uAlpha = GLES20.glGetUniformLocation(textureProgram, "uAlpha")

        solidProgram = buildProgram(SOLID_VERTEX_SHADER, SOLID_FRAGMENT_SHADER)
        aSolidPosition = GLES20.glGetAttribLocation(solidProgram, "aPosition")
        uSolidColor = GLES20.glGetUniformLocation(solidProgram, "uColor")

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    fun beginFrame() {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(backgroundColor[0], backgroundColor[1], backgroundColor[2], backgroundColor[3])
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    fun draw(command: DrawCommand, textureId: Int) {
        if (command.alpha <= 0f) return
        applyScissor(command.clip)

        fillQuad(command)
        GLES20.glUseProgram(textureProgram)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPosition)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoord)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniform1f(uAlpha, command.alpha.coerceIn(0f, 1f))

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /** Opening and closing fades. */
    fun drawBlackout(alpha: Float) {
        if (alpha <= 0f) return
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        quadBuffer.clear()
        quadBuffer.put(floatArrayOf(-1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f))
        quadBuffer.position(0)

        GLES20.glUseProgram(solidProgram)
        GLES20.glVertexAttribPointer(aSolidPosition, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
        GLES20.glEnableVertexAttribArray(aSolidPosition)
        GLES20.glUniform4f(uSolidColor, 0f, 0f, 0f, alpha.coerceIn(0f, 1f))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
        GLES20.glDisableVertexAttribArray(aSolidPosition)
    }

    fun createTexture(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    fun deleteTexture(textureId: Int) {
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    fun release() {
        if (textureProgram != 0) GLES20.glDeleteProgram(textureProgram)
        if (solidProgram != 0) GLES20.glDeleteProgram(solidProgram)
        textureProgram = 0
        solidProgram = 0
    }

    /** Clipping a photo to its slot: the slots are axis aligned, so the scissor box is enough. */
    private fun applyScissor(clip: NormRect?) {
        if (clip == null) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            return
        }
        val left = (clip.left * width).roundToInt()
        val right = (clip.right * width).roundToInt()
        // OpenGL counts rows from the bottom, the engine from the top.
        val bottom = ((1f - clip.bottom) * height).roundToInt()
        val top = ((1f - clip.top) * height).roundToInt()
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(left, bottom, (right - left).coerceAtLeast(0), (top - bottom).coerceAtLeast(0))
    }

    /**
     * Builds the four corners of the quad. Positions are rotated in pixel space around the center of
     * the destination rectangle, then converted to clip space.
     */
    private fun fillQuad(command: DrawCommand) {
        val dst = command.dst
        val centerX = dst.centerX * width
        val centerY = dst.centerY * height
        val halfWidth = dst.width * width / 2f
        val halfHeight = dst.height * height / 2f
        val radians = Math.toRadians(command.rotationDeg.toDouble())
        val cos = cos(radians).toFloat()
        val sin = sin(radians).toFloat()

        val src = command.src
        val corners = floatArrayOf(
            -halfWidth, -halfHeight, src.left, src.top,
            -halfWidth, halfHeight, src.left, src.bottom,
            halfWidth, -halfHeight, src.right, src.top,
            halfWidth, halfHeight, src.right, src.bottom,
        )

        vertexBuffer.clear()
        var index = 0
        while (index < corners.size) {
            val localX = corners[index]
            val localY = corners[index + 1]
            val pixelX = centerX + localX * cos - localY * sin
            val pixelY = centerY + localX * sin + localY * cos
            vertexBuffer.put(pixelX / width * 2f - 1f)
            vertexBuffer.put(1f - pixelY / height * 2f)
            vertexBuffer.put(corners[index + 2])
            vertexBuffer.put(corners[index + 3])
            index += 4
        }
        vertexBuffer.position(0)
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        check(program != 0) { "Impossible de créer le programme OpenGL" }
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("Édition de liens OpenGL impossible : $log")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Impossible de créer le shader" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Compilation du shader impossible : $log")
        }
        return shader
    }

    private fun allocateFloatBuffer(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private companion object {
        const val VERTEX_COUNT = 4
        const val FLOATS_PER_VERTEX = 4
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * Float.SIZE_BYTES

        val TEXTURE_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val TEXTURE_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(color.rgb, color.a * uAlpha);
            }
        """.trimIndent()

        val SOLID_VERTEX_SHADER = """
            attribute vec2 aPosition;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        val SOLID_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """.trimIndent()
    }
}
