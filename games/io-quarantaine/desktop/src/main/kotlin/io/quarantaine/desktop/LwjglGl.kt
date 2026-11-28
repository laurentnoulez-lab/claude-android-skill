package io.quarantaine.desktop

import io.quarantaine.platform.Gl
import org.lwjgl.opengles.GLES20
import org.lwjgl.opengles.GLES20.*
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** Implémentation de [Gl] via LWJGL (OpenGL ES 2/3 sur Mesa llvmpipe), pour les captures headless. */
class LwjglGl : Gl {
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = glClearColor(r, g, b, a)
    override fun clear(mask: Int) = glClear(mask)
    override fun viewport(x: Int, y: Int, w: Int, h: Int) = glViewport(x, y, w, h)
    override fun enable(cap: Int) = glEnable(cap)
    override fun disable(cap: Int) = glDisable(cap)
    override fun blendFunc(src: Int, dst: Int) = glBlendFunc(src, dst)
    override fun depthMask(flag: Boolean) = glDepthMask(flag)
    override fun depthFunc(func: Int) = glDepthFunc(func)
    override fun cullFace(mode: Int) = glCullFace(mode)
    override fun createShader(type: Int) = glCreateShader(type)
    override fun shaderSource(shader: Int, src: String) = glShaderSource(shader, src)
    override fun compileShader(shader: Int) = glCompileShader(shader)
    override fun shaderCompiled(shader: Int) = glGetShaderi(shader, GL_COMPILE_STATUS) != 0
    override fun shaderInfoLog(shader: Int): String = glGetShaderInfoLog(shader)
    override fun createProgram() = glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = glAttachShader(program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) = glBindAttribLocation(program, index, name)
    override fun linkProgram(program: Int) = glLinkProgram(program)
    override fun programLinked(program: Int) = glGetProgrami(program, GL_LINK_STATUS) != 0
    override fun programInfoLog(program: Int): String = glGetProgramInfoLog(program)
    override fun useProgram(program: Int) = glUseProgram(program)
    override fun getUniformLocation(program: Int, name: String) = glGetUniformLocation(program, name)
    override fun uniform1i(loc: Int, v: Int) = glUniform1i(loc, v)
    override fun uniform1f(loc: Int, v: Float) = glUniform1f(loc, v)
    override fun uniform2f(loc: Int, a: Float, b: Float) = glUniform2f(loc, a, b)
    override fun uniform3f(loc: Int, a: Float, b: Float, c: Float) = glUniform3f(loc, a, b, c)
    override fun uniform4fv(loc: Int, count: Int, v: FloatArray) = glUniform4fv(loc, v.copyOf(count * 4))
    override fun uniform3fv(loc: Int, count: Int, v: FloatArray) = glUniform3fv(loc, v.copyOf(count * 3))
    override fun uniformMatrix4fv(loc: Int, m: FloatArray) = glUniformMatrix4fv(loc, false, m)
    override fun genBuffer() = glGenBuffers()
    override fun bindBuffer(target: Int, id: Int) = glBindBuffer(target, id)
    override fun bufferData(target: Int, data: FloatBuffer, usage: Int) = glBufferData(target, data, usage)
    override fun bufferDataShort(target: Int, data: ShortBuffer, usage: Int) = glBufferData(target, data, usage)
    override fun enableVertexAttribArray(index: Int) = glEnableVertexAttribArray(index)
    override fun disableVertexAttribArray(index: Int) = glDisableVertexAttribArray(index)
    override fun vertexAttribPointer(index: Int, size: Int, stride: Int, offset: Int) = glVertexAttribPointer(index, size, GL_FLOAT, false, stride, offset.toLong())
    override fun drawArrays(mode: Int, first: Int, count: Int) = glDrawArrays(mode, first, count)
    override fun drawElementsShort(mode: Int, count: Int, offset: Int) = glDrawElements(mode, count, GL_UNSIGNED_SHORT, offset.toLong())
    override fun genTexture() = glGenTextures()
    override fun bindTexture(id: Int) = glBindTexture(GL_TEXTURE_2D, id)
    override fun activeTexture0() = glActiveTexture(GL_TEXTURE0)
    override fun texParameteri(pname: Int, param: Int) = glTexParameteri(GL_TEXTURE_2D, pname, param)
    override fun texImage2D(level: Int, w: Int, h: Int, rgba: ByteBuffer) = GLES20.glTexImage2D(GL_TEXTURE_2D, level, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba)
    override fun generateMipmap() = glGenerateMipmap(GL_TEXTURE_2D)
}
