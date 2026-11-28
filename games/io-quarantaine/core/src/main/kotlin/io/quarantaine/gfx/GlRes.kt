package io.quarantaine.gfx

import io.quarantaine.platform.GL
import io.quarantaine.platform.Gl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** Programme GLSL avec emplacements d'attributs fixes et cache des uniformes. */
class Program(private val gl: Gl, vs: String, fs: String, attribs: Array<String>) {
    val id: Int
    private val uniforms = HashMap<String, Int>()

    init {
        val v = compile(GL.VERTEX_SHADER, vs)
        val f = compile(GL.FRAGMENT_SHADER, fs)
        id = gl.createProgram()
        gl.attachShader(id, v)
        gl.attachShader(id, f)
        attribs.forEachIndexed { i, n -> gl.bindAttribLocation(id, i, n) }
        gl.linkProgram(id)
        if (!gl.programLinked(id)) throw IllegalStateException("Link: " + gl.programInfoLog(id))
    }

    private fun compile(type: Int, src: String): Int {
        val s = gl.createShader(type)
        gl.shaderSource(s, src)
        gl.compileShader(s)
        if (!gl.shaderCompiled(s)) throw IllegalStateException("Shader: " + gl.shaderInfoLog(s) + "\n" + src)
        return s
    }

    fun u(name: String): Int = uniforms.getOrPut(name) { gl.getUniformLocation(id, name) }
    fun use() = gl.useProgram(id)
}

object GlUtil {
    fun floatBuffer(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    fun shortBuffer(n: Int): ShortBuffer = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

    /** Envoie une image avec mipmaps. [pixelated] : agrandissement sans lissage (style rétro). */
    fun upload(gl: Gl, img: Img, pixelated: Boolean, repeat: Boolean, mipmaps: Boolean = true): Int {
        val id = gl.genTexture()
        gl.bindTexture(id)
        gl.texImage2D(0, img.w, img.h, img.toRgbaBuffer())
        if (mipmaps) {
            // Mipmaps calculés sur le CPU (résultat identique sur tous les GPU)
            var level = 1
            var cur = img
            while (cur.w > 1 || cur.h > 1) {
                cur = cur.half()
                gl.texImage2D(level++, cur.w, cur.h, cur.toRgbaBuffer())
            }
        }
        gl.texParameteri(GL.TEXTURE_MAG_FILTER, if (pixelated) GL.NEAREST else GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_MIN_FILTER, when {
            !mipmaps -> if (pixelated) GL.NEAREST else GL.LINEAR
            pixelated -> GL.NEAREST_MIPMAP_LINEAR
            else -> GL.LINEAR_MIPMAP_LINEAR
        })
        val wrap = if (repeat) GL.REPEAT else GL.CLAMP_TO_EDGE
        gl.texParameteri(GL.TEXTURE_WRAP_S, wrap)
        gl.texParameteri(GL.TEXTURE_WRAP_T, wrap)
        return id
    }
}

/** Liste de flottants extensible sans allocation par élément. */
class FloatList(cap: Int = 1024) {
    var data = FloatArray(cap)
    var size = 0

    fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }

    fun add(v: Float) { ensure(1); data[size++] = v }
    fun clear() { size = 0 }
}

class ShortList(cap: Int = 1024) {
    var data = ShortArray(cap)
    var size = 0
    fun add(v: Int) {
        if (size + 1 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = v.toShort()
    }
}
