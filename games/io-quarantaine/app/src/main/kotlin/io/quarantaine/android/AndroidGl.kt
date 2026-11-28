package io.quarantaine.android

import android.opengl.GLES20
import io.quarantaine.platform.Gl
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** Implémentation de [Gl] avec OpenGL ES 2.0 d'Android. */
class AndroidGl : Gl {
    private val tmp = IntArray(1)

    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GLES20.glClearColor(r, g, b, a)
    override fun clear(mask: Int) = GLES20.glClear(mask)
    override fun viewport(x: Int, y: Int, w: Int, h: Int) = GLES20.glViewport(x, y, w, h)
    override fun enable(cap: Int) = GLES20.glEnable(cap)
    override fun disable(cap: Int) = GLES20.glDisable(cap)
    override fun blendFunc(src: Int, dst: Int) = GLES20.glBlendFunc(src, dst)
    override fun depthMask(flag: Boolean) = GLES20.glDepthMask(flag)
    override fun depthFunc(func: Int) = GLES20.glDepthFunc(func)
    override fun cullFace(mode: Int) = GLES20.glCullFace(mode)
    override fun createShader(type: Int) = GLES20.glCreateShader(type)
    override fun shaderSource(shader: Int, src: String) = GLES20.glShaderSource(shader, src)
    override fun compileShader(shader: Int) = GLES20.glCompileShader(shader)
    override fun shaderCompiled(shader: Int): Boolean { GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, tmp, 0); return tmp[0] != 0 }
    override fun shaderInfoLog(shader: Int): String = GLES20.glGetShaderInfoLog(shader)
    override fun createProgram() = GLES20.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = GLES20.glAttachShader(program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) = GLES20.glBindAttribLocation(program, index, name)
    override fun linkProgram(program: Int) = GLES20.glLinkProgram(program)
    override fun programLinked(program: Int): Boolean { GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, tmp, 0); return tmp[0] != 0 }
    override fun programInfoLog(program: Int): String = GLES20.glGetProgramInfoLog(program)
    override fun useProgram(program: Int) = GLES20.glUseProgram(program)
    override fun getUniformLocation(program: Int, name: String) = GLES20.glGetUniformLocation(program, name)
    override fun uniform1i(loc: Int, v: Int) = GLES20.glUniform1i(loc, v)
    override fun uniform1f(loc: Int, v: Float) = GLES20.glUniform1f(loc, v)
    override fun uniform2f(loc: Int, a: Float, b: Float) = GLES20.glUniform2f(loc, a, b)
    override fun uniform3f(loc: Int, a: Float, b: Float, c: Float) = GLES20.glUniform3f(loc, a, b, c)
    override fun uniform4fv(loc: Int, count: Int, v: FloatArray) = GLES20.glUniform4fv(loc, count, v, 0)
    override fun uniform3fv(loc: Int, count: Int, v: FloatArray) = GLES20.glUniform3fv(loc, count, v, 0)
    override fun uniformMatrix4fv(loc: Int, m: FloatArray) = GLES20.glUniformMatrix4fv(loc, 1, false, m, 0)
    override fun genBuffer(): Int { GLES20.glGenBuffers(1, tmp, 0); return tmp[0] }
    override fun bindBuffer(target: Int, id: Int) = GLES20.glBindBuffer(target, id)
    override fun bufferData(target: Int, data: FloatBuffer, usage: Int) = GLES20.glBufferData(target, data.remaining() * 4, data, usage)
    override fun bufferDataShort(target: Int, data: ShortBuffer, usage: Int) = GLES20.glBufferData(target, data.remaining() * 2, data, usage)
    override fun enableVertexAttribArray(index: Int) = GLES20.glEnableVertexAttribArray(index)
    override fun disableVertexAttribArray(index: Int) = GLES20.glDisableVertexAttribArray(index)
    override fun vertexAttribPointer(index: Int, size: Int, stride: Int, offset: Int) = GLES20.glVertexAttribPointer(index, size, GLES20.GL_FLOAT, false, stride, offset)
    override fun drawArrays(mode: Int, first: Int, count: Int) = GLES20.glDrawArrays(mode, first, count)
    override fun drawElementsShort(mode: Int, count: Int, offset: Int) = GLES20.glDrawElements(mode, count, GLES20.GL_UNSIGNED_SHORT, offset)
    override fun genTexture(): Int { GLES20.glGenTextures(1, tmp, 0); return tmp[0] }
    override fun bindTexture(id: Int) = GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
    override fun activeTexture0() = GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    override fun texParameteri(pname: Int, param: Int) = GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, pname, param)
    override fun texImage2D(level: Int, w: Int, h: Int, rgba: ByteBuffer) =
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, level, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba)
    override fun generateMipmap() = GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
}
