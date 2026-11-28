package io.quarantaine.platform

import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Sous-ensemble d'OpenGL ES 2.0 utilisé par le jeu.
 * Implémenté par android.opengl.GLES20 sur téléphone et par LWJGL pour le banc de test headless.
 */
interface Gl {
    fun clearColor(r: Float, g: Float, b: Float, a: Float)
    fun clear(mask: Int)
    fun viewport(x: Int, y: Int, w: Int, h: Int)
    fun enable(cap: Int)
    fun disable(cap: Int)
    fun blendFunc(src: Int, dst: Int)
    fun depthMask(flag: Boolean)
    fun depthFunc(func: Int)
    fun cullFace(mode: Int)

    fun createShader(type: Int): Int
    fun shaderSource(shader: Int, src: String)
    fun compileShader(shader: Int)
    fun shaderCompiled(shader: Int): Boolean
    fun shaderInfoLog(shader: Int): String
    fun createProgram(): Int
    fun attachShader(program: Int, shader: Int)
    fun bindAttribLocation(program: Int, index: Int, name: String)
    fun linkProgram(program: Int)
    fun programLinked(program: Int): Boolean
    fun programInfoLog(program: Int): String
    fun useProgram(program: Int)
    fun getUniformLocation(program: Int, name: String): Int

    fun uniform1i(loc: Int, v: Int)
    fun uniform1f(loc: Int, v: Float)
    fun uniform2f(loc: Int, a: Float, b: Float)
    fun uniform3f(loc: Int, a: Float, b: Float, c: Float)
    fun uniform4fv(loc: Int, count: Int, v: FloatArray)
    fun uniform3fv(loc: Int, count: Int, v: FloatArray)
    fun uniformMatrix4fv(loc: Int, m: FloatArray)

    fun genBuffer(): Int
    fun bindBuffer(target: Int, id: Int)
    fun bufferData(target: Int, data: FloatBuffer, usage: Int)
    fun bufferDataShort(target: Int, data: ShortBuffer, usage: Int)
    fun enableVertexAttribArray(index: Int)
    fun disableVertexAttribArray(index: Int)
    fun vertexAttribPointer(index: Int, size: Int, stride: Int, offset: Int)
    fun drawArrays(mode: Int, first: Int, count: Int)
    fun drawElementsShort(mode: Int, count: Int, offset: Int)

    fun genTexture(): Int
    fun bindTexture(id: Int)
    fun activeTexture0()
    fun texParameteri(pname: Int, param: Int)
    fun texImage2D(level: Int, w: Int, h: Int, rgba: ByteBuffer)
    fun generateMipmap()
}

/** Constantes OpenGL ES 2.0 (valeurs normalisées Khronos). */
object GL {
    const val COLOR_BUFFER_BIT = 0x4000
    const val DEPTH_BUFFER_BIT = 0x0100
    const val DEPTH_TEST = 0x0B71
    const val BLEND = 0x0BE2
    const val CULL_FACE = 0x0B44
    const val ZERO = 0
    const val ONE = 1
    const val SRC_ALPHA = 0x0302
    const val ONE_MINUS_SRC_ALPHA = 0x0303
    const val LEQUAL = 0x0203
    const val LESS = 0x0201
    const val BACK = 0x0405
    const val VERTEX_SHADER = 0x8B31
    const val FRAGMENT_SHADER = 0x8B30
    const val ARRAY_BUFFER = 0x8892
    const val ELEMENT_ARRAY_BUFFER = 0x8893
    const val STATIC_DRAW = 0x88E4
    const val STREAM_DRAW = 0x88E0
    const val TRIANGLES = 0x0004
    const val TEXTURE_MIN_FILTER = 0x2801
    const val TEXTURE_MAG_FILTER = 0x2800
    const val TEXTURE_WRAP_S = 0x2802
    const val TEXTURE_WRAP_T = 0x2803
    const val NEAREST = 0x2600
    const val LINEAR = 0x2601
    const val NEAREST_MIPMAP_LINEAR = 0x2702
    const val LINEAR_MIPMAP_LINEAR = 0x2703
    const val REPEAT = 0x2901
    const val CLAMP_TO_EDGE = 0x812F
}
