package io.quarantaine.gfx

import io.quarantaine.game.Enemies
import io.quarantaine.game.Projectile
import io.quarantaine.game.World
import io.quarantaine.math.Mat4
import io.quarantaine.platform.GL
import io.quarantaine.platform.Gl
import io.quarantaine.world.Level
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Caméra libre (menus) ou caméra du joueur. */
class Camera {
    var x = 0f; var y = 0f; var z = 0f
    var yaw = 0f; var pitch = 0f
}

class Renderer(private val gl: Gl) {
    private lateinit var worldProg: Program
    private lateinit var fxProg: Program
    private lateinit var skyProg: Program
    lateinit var hudProg: Program
    val textures = IntArray(Tex.COUNT)
    lateinit var stream: StreamBuffer
    private var skyVbo = 0

    private class GpuChunk(val tex: Int, val vbo: Int, val ibo: Int, val count: Int)
    private val gpuChunks = ArrayList<GpuChunk>()
    private var meshLevel: Level? = null
    private var cpuChunks: List<MeshChunk> = emptyList()

    private val dyn = DynBatch()
    private val vm = DynBatch()
    private val fx = FxBatch()
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val lp = FloatArray(16)
    private val lc = FloatArray(12)

    var width = 1
    var height = 1

    /** (Re)création de toutes les ressources GL (au démarrage et après perte de contexte). */
    fun init() {
        worldProg = Program(gl, Shaders.WORLD_VS, Shaders.WORLD_FS, arrayOf("a_pos", "a_uv", "a_col"))
        fxProg = Program(gl, Shaders.FX_VS, Shaders.FX_FS, arrayOf("a_pos", "a_uv", "a_col"))
        skyProg = Program(gl, Shaders.SKY_VS, Shaders.SKY_FS, arrayOf("a_pos"))
        hudProg = Program(gl, Shaders.HUD_VS, Shaders.HUD_FS, arrayOf("a_pos", "a_uv", "a_col"))
        for (t in 0 until Tex.COUNT) {
            val img = Tex.generate(t)
            textures[t] = GlUtil.upload(gl, img, pixelated = t != Tex.GLOW && t != Tex.WHITE, repeat = t != Tex.GLOW)
        }
        stream = StreamBuffer(gl)
        skyVbo = gl.genBuffer()
        val fb = GlUtil.floatBuffer(12)
        fb.put(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)); fb.flip()
        gl.bindBuffer(GL.ARRAY_BUFFER, skyVbo)
        gl.bufferData(GL.ARRAY_BUFFER, fb, GL.STATIC_DRAW)
        gpuChunks.clear()
        if (cpuChunks.isNotEmpty()) uploadChunks()
        gl.activeTexture0()
    }

    /** Construit (CPU) puis envoie (GPU) le maillage du niveau. */
    fun setLevel(level: Level) {
        if (meshLevel === level) return
        meshLevel = level
        cpuChunks = WorldMesh(level).build()
        uploadChunks()
    }

    private fun uploadChunks() {
        gpuChunks.clear()
        for (c in cpuChunks) {
            val vbo = gl.genBuffer()
            val fb = GlUtil.floatBuffer(c.verts.size)
            fb.put(c.verts); fb.flip()
            gl.bindBuffer(GL.ARRAY_BUFFER, vbo)
            gl.bufferData(GL.ARRAY_BUFFER, fb, GL.STATIC_DRAW)
            val ibo = gl.genBuffer()
            val sb = GlUtil.shortBuffer(c.indices.size)
            sb.put(c.indices); sb.flip()
            gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, ibo)
            gl.bufferDataShort(GL.ELEMENT_ARRAY_BUFFER, sb, GL.STATIC_DRAW)
            gpuChunks.add(GpuChunk(c.tex, vbo, ibo, c.indices.size))
        }
        gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, 0)
    }

    /** Dessine la scène 3D vue depuis [cam]. */
    fun render(world: World, cam: Camera, time: Float, drawWeapon: Boolean) {
        val level = world.level
        setLevel(level)
        val def = level.def
        gl.viewport(0, 0, width, height)
        gl.clearColor(def.fog[0], def.fog[1], def.fog[2], 1f)
        gl.clear(GL.COLOR_BUFFER_BIT or GL.DEPTH_BUFFER_BIT)

        val aspect = width.toFloat() / height
        val vfov = (2f * atan(tan(Math.toRadians(50.0).toFloat()) / aspect)).coerceIn(0.96f, 1.4f)
        Mat4.perspective(proj, vfov, aspect, 0.04f, 90f)
        val cp = cos(cam.pitch)
        val fx0 = cos(cam.yaw) * cp; val fy0 = sin(cam.yaw) * cp; val fz0 = sin(cam.pitch)
        // droite = f x haut(0,0,1)
        var rx = fy0; var ry = -fx0
        val rl = sqrt(rx * rx + ry * ry).coerceAtLeast(1e-4f); rx /= rl; ry /= rl
        val ux = ry * fz0 - 0f * fy0; val uy = 0f * fx0 - rx * fz0; val uz = rx * fy0 - ry * fx0
        Mat4.lookDir(view, cam.x, cam.y, cam.z, fx0, fy0, fz0, 0f, 0f, 1f)
        Mat4.mul(vp, proj, view)

        // Ciel
        if (level.hasSky) {
            gl.disable(GL.DEPTH_TEST)
            gl.depthMask(false)
            skyProg.use()
            gl.uniform3f(skyProg.u("u_fwd"), fx0, fy0, fz0)
            gl.uniform3f(skyProg.u("u_right"), rx, ry, 0f)
            gl.uniform3f(skyProg.u("u_up"), ux, uy, uz)
            val ty = tan(vfov / 2f)
            gl.uniform2f(skyProg.u("u_tan"), ty * aspect, ty)
            gl.uniform1f(skyProg.u("u_time"), time)
            gl.bindBuffer(GL.ARRAY_BUFFER, skyVbo)
            gl.enableVertexAttribArray(0)
            gl.disableVertexAttribArray(1); gl.disableVertexAttribArray(2)
            gl.vertexAttribPointer(0, 2, 8, 0)
            gl.drawArrays(GL.TRIANGLES, 0, 6)
            gl.depthMask(true)
        }
        gl.enableVertexAttribArray(0); gl.enableVertexAttribArray(1); gl.enableVertexAttribArray(2)

        pickLights(world, cam)
        worldProg.use()
        setWorldUniforms(vp, cam, def.fog, def.fogDensity)
        gl.enable(GL.DEPTH_TEST)
        gl.depthFunc(GL.LEQUAL)
        gl.enable(GL.CULL_FACE)
        gl.cullFace(GL.BACK)
        val uScroll = worldProg.u("u_scroll")
        for (c in gpuChunks) {
            if (c.tex == Tex.LAVA) gl.uniform2f(uScroll, time * 0.05f, sin(time * 0.4f) * 0.08f)
            gl.bindTexture(textures[c.tex])
            gl.bindBuffer(GL.ARRAY_BUFFER, c.vbo)
            gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, c.ibo)
            gl.vertexAttribPointer(0, 3, 32, 0)
            gl.vertexAttribPointer(1, 2, 32, 12)
            gl.vertexAttribPointer(2, 3, 32, 20)
            gl.drawElementsShort(GL.TRIANGLES, c.count, 0)
            if (c.tex == Tex.LAVA) gl.uniform2f(uScroll, 0f, 0f)
        }
        gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, 0)
        gl.disable(GL.CULL_FACE)

        // Objets dynamiques
        dyn.clear()
        fx.clear()
        fx.rx = rx; fx.ry = ry; fx.rz = 0f; fx.ux = ux; fx.uy = uy; fx.uz = uz
        buildDynamic(world, cam, time, fx0, fy0)
        dyn.draw(gl, stream, textures)

        // Effets additifs
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.SRC_ALPHA, GL.ONE)
        gl.depthMask(false)
        fxProg.use()
        gl.uniformMatrix4fv(fxProg.u("u_mvp"), vp)
        gl.uniform3f(fxProg.u("u_cam"), cam.x, cam.y, cam.z)
        gl.uniform1f(fxProg.u("u_fogd"), def.fogDensity)
        gl.uniform1i(fxProg.u("u_tex"), 0)
        gl.bindTexture(textures[Tex.GLOW])
        fx.draw(gl, stream)
        gl.depthMask(true)
        gl.disable(GL.BLEND)

        // Arme en vue subjective
        if (drawWeapon) {
            gl.clear(GL.DEPTH_BUFFER_BIT)
            Mat4.perspective(proj, 1.15f, aspect, 0.01f, 4f)
            Mat4.mul(vp, proj, view)
            worldProg.use()
            // Lumières dynamiques atténuées sur l'arme (évite la surexposition par le flash du tir)
            for (i in lc.indices) lc[i] *= 0.3f
            setWorldUniforms(vp, cam, def.fog, def.fogDensity)
            vm.clear()
            val p = world.player
            val c = level.cellAt(p.x, p.y)
            val lr = (c?.lr ?: 0.5f) * 0.9f + 0.3f; val lg = (c?.lg ?: 0.5f) * 0.9f + 0.3f; val lb = (c?.lb ?: 0.5f) * 0.9f + 0.3f
            Models.viewWeapon(vm, p, time, fx0, fy0, fz0, rx, ry, 0f, ux, uy, uz, cam.x, cam.y, cam.z, lr, lg, lb)
            vm.draw(gl, stream, textures)
        }
    }

    private fun setWorldUniforms(m: FloatArray, cam: Camera, fog: FloatArray, fogd: Float) {
        gl.uniformMatrix4fv(worldProg.u("u_mvp"), m)
        gl.uniform3f(worldProg.u("u_cam"), cam.x, cam.y, cam.z)
        gl.uniform3f(worldProg.u("u_fog"), fog[0], fog[1], fog[2])
        gl.uniform1f(worldProg.u("u_fogd"), fogd)
        gl.uniform2f(worldProg.u("u_scroll"), 0f, 0f)
        gl.uniform1i(worldProg.u("u_tex"), 0)
        gl.uniform4fv(worldProg.u("u_lp[0]"), 4, lp)
        gl.uniform3fv(worldProg.u("u_lc[0]"), 4, lc)
    }

    private val candX = FloatArray(64); private val candY = FloatArray(64); private val candZ = FloatArray(64)
    private val candR = FloatArray(64); private val candCr = FloatArray(64); private val candCg = FloatArray(64); private val candCb = FloatArray(64)
    private val candS = FloatArray(64)

    /** Sélection des 4 lumières dynamiques les plus influentes. */
    private fun pickLights(world: World, cam: Camera) {
        var n = 0
        fun add(x: Float, y: Float, z: Float, r: Float, cr: Float, cg: Float, cb: Float) {
            if (n >= 64) return
            val dx = x - cam.x; val dy = y - cam.y; val dz = z - cam.z
            candX[n] = x; candY[n] = y; candZ[n] = z; candR[n] = r; candCr[n] = cr; candCg[n] = cg; candCb[n] = cb
            candS[n] = (cr + cg + cb) * r / (1f + (dx * dx + dy * dy + dz * dz) / 16f)
            n++
        }
        for (l in world.lights) {
            val k = (l.life / l.maxLife).coerceIn(0f, 1f)
            add(l.x, l.y, l.z, l.radius, l.r * k, l.g * k, l.b * k)
        }
        for (p in world.projectiles) when (p.kind) {
            Projectile.ROCKET, Projectile.BOSS_ROCKET -> add(p.x, p.y, p.z, 3f, 1.2f, 0.6f, 0.2f)
            Projectile.ANNIHIL -> add(p.x, p.y, p.z, 6f, 0.5f, 1.5f, 0.6f)
            Projectile.ACID -> add(p.x, p.y, p.z, 2f, 0.3f, 0.8f, 0.1f)
            Projectile.PLASMA -> add(p.x, p.y, p.z, 2.5f, 1f, 0.25f, 0.2f)
        }
        for (i in 0 until 4) {
            var best = -1; var bs = 0f
            for (j in 0 until n) if (candS[j] > bs) { bs = candS[j]; best = j }
            if (best >= 0) {
                lp[i * 4] = candX[best]; lp[i * 4 + 1] = candY[best]; lp[i * 4 + 2] = candZ[best]; lp[i * 4 + 3] = candR[best]
                lc[i * 3] = candCr[best]; lc[i * 3 + 1] = candCg[best]; lc[i * 3 + 2] = candCb[best]
                candS[best] = -1f
            } else {
                lp[i * 4] = 0f; lp[i * 4 + 1] = 0f; lp[i * 4 + 2] = -100f; lp[i * 4 + 3] = 1f
                lc[i * 3] = 0f; lc[i * 3 + 1] = 0f; lc[i * 3 + 2] = 0f
            }
        }
    }

    private fun visible(cam: Camera, x: Float, y: Float, fx: Float, fy: Float, maxD: Float): Boolean {
        val dx = x - cam.x; val dy = y - cam.y
        val d2 = dx * dx + dy * dy
        if (d2 > maxD * maxD) return false
        if (d2 < 4f) return true
        return (dx * fx + dy * fy) > -0.2f * sqrt(d2)
    }

    private fun buildDynamic(world: World, cam: Camera, time: Float, fx0: Float, fy0: Float) {
        val level = world.level
        val fl = sqrt(fx0 * fx0 + fy0 * fy0).coerceAtLeast(1e-3f)
        val dirX = fx0 / fl; val dirY = fy0 / fl
        for (d in level.doors) {
            if (!visible(cam, d.cx + 0.5f, d.cy + 0.5f, dirX, dirY, 50f)) continue
            val c = level.cell(d.cx, d.cy)!!
            Models.door(dyn, d, d.tex, c.lr, c.lg, c.lb)
        }
        for (e in world.enemies) {
            if (!visible(cam, e.x, e.y, dirX, dirY, 45f)) continue
            val c = level.cellAt(e.x, e.y)
            Models.enemy(dyn, e, time, c?.lr ?: 0.5f, c?.lg ?: 0.5f, c?.lb ?: 0.5f)
            if (e.alive) when (e.type.id) {
                Enemies.DRONE -> fx.sprite(e.x, e.y, e.z - 0.02f, 0.14f, 0.3f, 0.6f, 1f, 0.8f)
                Enemies.BOSS -> fx.sprite(e.x + cos(e.yaw) * 0.55f, e.y + sin(e.yaw) * 0.55f, e.z + 1.55f, 0.5f + 0.1f * sin(time * 6f), 0.5f, 1f, 0.3f, 0.6f)
                Enemies.SPITTER -> if (e.state == 2) fx.sprite(e.x + cos(e.yaw) * 0.2f, e.y + sin(e.yaw) * 0.2f, e.z + 0.54f, 0.12f, 0.4f, 1f, 0.2f, 0.8f)
            }
        }
        for (pr in world.props) {
            if (!visible(cam, pr.x, pr.y, dirX, dirY, 50f)) continue
            val c = level.cellAt(pr.x, pr.y)
            Models.lightPillar(dyn, pr.x, pr.y, pr.z, time, c?.lr ?: 0.5f, c?.lg ?: 0.5f, c?.lb ?: 0.5f)
            fx.sprite(pr.x, pr.y, pr.z + 1.0f, 0.5f, 0.2f, 0.5f, 0.6f, 0.35f)
        }
        for (p in world.pickups) {
            if (p.taken || !visible(cam, p.x, p.y, dirX, dirY, 35f)) continue
            val c = level.cellAt(p.x, p.y)
            Models.pickup(dyn, p, time, c?.lr ?: 0.5f, c?.lg ?: 0.5f, c?.lb ?: 0.5f)
            when (p.ch) {
                'r' -> fx.sprite(p.x, p.y, p.z + 0.25f, 0.25f, 1f, 0.2f, 0.1f, 0.6f)
                'u' -> fx.sprite(p.x, p.y, p.z + 0.25f, 0.25f, 0.2f, 0.4f, 1f, 0.6f)
                'y' -> fx.sprite(p.x, p.y, p.z + 0.25f, 0.25f, 1f, 0.85f, 0.1f, 0.6f)
                'U' -> fx.sprite(p.x, p.y, p.z + 0.25f, 0.4f, 0.3f, 1f, 0.4f, 0.5f)
            }
        }
        dyn.setLight(1f, 1f, 1f)
        for (p in world.projectiles) {
            Models.projectile(dyn, p, time)
            when (p.kind) {
                Projectile.ROCKET, Projectile.BOSS_ROCKET -> fx.sprite(p.x - p.vx * 0.012f, p.y - p.vy * 0.012f, p.z - p.vz * 0.012f, 0.18f, 1f, 0.6f, 0.2f, 0.9f)
                Projectile.ANNIHIL -> fx.sprite(p.x, p.y, p.z, 0.7f, 0.4f, 1f, 0.5f, 0.8f)
                Projectile.ACID -> fx.sprite(p.x, p.y, p.z, 0.22f, 0.4f, 1f, 0.2f, 0.7f)
                Projectile.PLASMA -> fx.sprite(p.x, p.y, p.z, 0.25f, 1f, 0.3f, 0.25f, 0.8f)
            }
        }
        val d = world.debris
        for (i in 0 until d.count) {
            val c = level.cellAt(d.x[i], d.y[i])
            dyn.setLight(c?.lr ?: 0.5f, c?.lg ?: 0.5f, c?.lb ?: 0.5f)
            Models.debrisCube(dyn, d.x[i], d.y[i], d.z[i], d.size[i], d.rot[i], d.r[i], d.g[i], d.b[i])
        }
        val g = world.glows
        for (i in 0 until g.count) {
            val a = (g.life[i] / g.maxLife[i]).coerceIn(0f, 1f)
            fx.sprite(g.x[i], g.y[i], g.z[i], g.size[i], g.r[i], g.g[i], g.b[i], a)
        }
        val p = world.player
        if (p.flash > 0f) {
            val cp = cos(p.pitch)
            fx.sprite(cam.x + cos(p.yaw) * cp * 0.8f + sin(p.yaw) * 0.12f, cam.y + sin(p.yaw) * cp * 0.8f - cos(p.yaw) * 0.12f, cam.z + sin(p.pitch) * 0.8f - 0.12f, 0.16f, 1f, 0.8f, 0.4f, 0.9f)
        }
    }
}
