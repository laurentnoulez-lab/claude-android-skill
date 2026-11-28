package io.quarantaine.desktop

import io.quarantaine.game.NoEvents
import io.quarantaine.game.World
import io.quarantaine.gfx.Camera
import io.quarantaine.gfx.Renderer
import io.quarantaine.world.Levels
import org.lwjgl.BufferUtils
import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL10.*
import org.lwjgl.egl.EGL12
import org.lwjgl.opengles.GLES
import org.lwjgl.opengles.GLES20.*
import org.lwjgl.system.Configuration
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Contexte OpenGL ES hors écran (Mesa, plateforme « surfaceless ») avec un framebuffer de rendu. */
class HeadlessContext(val w: Int, val h: Int) {
    init {
        Configuration.EGL_LIBRARY_NAME.set("libEGL.so.1")
        Configuration.OPENGLES_LIBRARY_NAME.set("libGLESv2.so.2")
        val dpy = eglGetDisplay(0L)
        val maj = IntArray(1); val min = IntArray(1)
        check(eglInitialize(dpy, maj, min)) { "eglInitialize ${eglGetError()} (lancer avec EGL_PLATFORM=surfaceless)" }
        EGL.createDisplayCapabilities(dpy, maj[0], min[0])
        val cfgs = BufferUtils.createPointerBuffer(1); val n = IntArray(1)
        check(eglChooseConfig(dpy, intArrayOf(EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, 0x3040, 0x0004, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE), cfgs, n) && n[0] > 0)
        EGL12.eglBindAPI(0x30A0)
        val ctx = eglCreateContext(dpy, cfgs[0], EGL_NO_CONTEXT, intArrayOf(0x3098, 2, EGL_NONE))
        check(eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx))
        GLES.createCapabilities()
        val fbo = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        val rb = glGenRenderbuffers(); glBindRenderbuffer(GL_RENDERBUFFER, rb)
        glRenderbufferStorage(GL_RENDERBUFFER, 0x8058, w, h)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rb)
        val db = glGenRenderbuffers(); glBindRenderbuffer(GL_RENDERBUFFER, db)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, w, h)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, db)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE)
    }

    fun save(file: File) {
        val buf = BufferUtils.createByteBuffer(w * h * 4)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val i = ((h - 1 - y) * w + x) * 4
            val r = buf.get(i).toInt() and 255; val g = buf.get(i + 1).toInt() and 255; val b = buf.get(i + 2).toInt() and 255
            img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
        }
        file.parentFile?.mkdirs()
        ImageIO.write(img, "png", file)
        println("capture : ${file.absolutePath}")
    }
}

class MemoryHost : io.quarantaine.platform.Host {
    val data = HashMap<String, String>()
    override fun load(key: String) = data[key]
    override fun save(key: String, value: String) { data[key] = value }
    override fun vibrate(ms: Int, strength: Float) {}
    override fun quit() {}
    override fun log(msg: String) = println(msg)
}

fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "desktop/out" })
    val only = args.getOrNull(1)
    val ctx = HeadlessContext(1280, 576)
    val gl = LwjglGl()
    val game = io.quarantaine.Game(MemoryHost(), 44100)
    game.onSurfaceCreated(gl)
    game.onSurfaceChanged(ctx.w, ctx.h)
    fun frames(n: Int) { repeat(n) { game.onDrawFrame(1f / 60f) } }
    fun touch(a: Int, id: Int, x: Float, y: Float) = game.onTouch(io.quarantaine.platform.TouchEvent(a, id, x, y))
    fun shot(name: String) { glFinish(); ctx.save(File(out, "$name.png")) }
    fun place(x: Float, y: Float, yaw: Float, pitch: Float = 0f) {
        val p = game.world!!.player
        p.x = x; p.y = y; p.yaw = yaw * 3.14159f / 180f; p.pitch = pitch
        p.z = game.world!!.phys.groundUnder(x, y, 0.2f, 5f); p.eyeZ = p.z + World.EYE
    }
    val all = only == null
    if (all || only == "menus") {
        frames(90); shot("01_titre")
        game.debugSetState(io.quarantaine.Game.State.DIFFICULTY); frames(5); shot("02_difficulte")
        game.debugSetState(io.quarantaine.Game.State.OPTIONS); frames(5); shot("03_options")
        game.debugSetState(io.quarantaine.Game.State.HELP); frames(5); shot("04_aide")
        game.startNewGame(1); frames(150); shot("05_briefing")
    }
    if (all || only == "l1") {
        game.debugStart(0, 1, false); frames(3)
        touch(0, 1, 200f, 420f); frames(2); touch(1, 1, 260f, 380f)
        touch(0, 2, 1280f - 576f * 0.2f, 576f * 0.75f)
        frames(12); shot("10_jeu_hud")
        touch(2, 1, 260f, 380f); touch(2, 2, 1280f - 576f * 0.2f, 576f * 0.75f); frames(30)
        place(24.5f, 18.5f, 90f, 0.05f); frames(4); shot("11_hub")
        place(24.5f, 8.5f, 60f, 0f); frames(4); shot("12_entrepot")
        place(4.5f, 4.5f, 90f, 0.1f); frames(4); shot("13_maintenance")
        place(9.5f, 21.5f, 100f, 0.25f); frames(2); shot("14_exterieur_jupiter")
        touch(0, 5, 576f * 0.21f, 576f * 0.08f); touch(2, 5, 576f * 0.21f, 576f * 0.08f); frames(3); shot("15_carte")
        touch(0, 5, 576f * 0.21f, 576f * 0.08f); touch(2, 5, 576f * 0.21f, 576f * 0.08f); frames(2)
        touch(0, 6, 576f * 0.08f, 576f * 0.08f); touch(2, 6, 576f * 0.08f, 576f * 0.08f); frames(3); shot("16_pause")
    }
    if (all || only == "weapons") {
        game.debugStart(0, 1, true); frames(3)
        place(28.0f, 18.0f, 90f, 0f)
        for (w in 0 until 6) {
            game.debugInput().select = w; frames(40)
            game.debugInput().select = -1
            touch(0, 9, 1280f - 576f * 0.2f, 576f * 0.75f); frames(if (w == 5) 24 else 3)
            shot("2${w}_arme_$w"); touch(2, 9, 1280f - 576f * 0.2f, 576f * 0.75f); frames(60)
        }
    }
    if (all || only == "l2") {
        game.debugStart(1, 1, true); frames(3)
        place(12.5f, 22.5f, 0f, 0.05f); frames(30); shot("30_raffinerie")
        place(36.5f, 22.5f, 0f, 0.05f); frames(4); shot("31_station_pompage")
    }
    if (all || only == "l3") {
        game.debugStart(2, 1, true); frames(3)
        place(23.5f, 15.5f, 90f, 0.05f); frames(4); shot("40_atrium")
        place(40.5f, 19.5f, 90f, 0.05f); frames(4); shot("41_xenobio")
        place(6.5f, 25.5f, 90f, 0.0f); frames(4); shot("42_cryo")
    }
    if (all || only == "l4") {
        game.debugStart(3, 1, true); frames(3)
        place(19.5f, 11.5f, 90f, 0.12f); frames(4); shot("50_arene_boss")
        game.debugInput().select = 4; frames(40); game.debugInput().select = -1
        touch(0, 9, 1280f - 576f * 0.2f, 576f * 0.75f); frames(25); shot("51_boss_roquettes")
        touch(2, 9, 1280f - 576f * 0.2f, 576f * 0.75f); frames(120); shot("52_boss_combat")
    }
}
