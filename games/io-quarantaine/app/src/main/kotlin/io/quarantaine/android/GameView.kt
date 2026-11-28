package io.quarantaine.android

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import io.quarantaine.Game
import io.quarantaine.platform.TouchEvent
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

/** Surface OpenGL ES 2.0 : rendu à résolution plafonnée (720p) pour garantir la fluidité. */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val game: Game) : GLSurfaceView(context) {
    private val gl = AndroidGl()
    @Volatile private var surfW = 1
    @Volatile private var surfH = 1

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            private var last = 0L
            override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
                game.onSurfaceCreated(gl)
                last = System.nanoTime()
            }
            override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
                game.onSurfaceChanged(width, height)
            }
            override fun onDrawFrame(unused: GL10?) {
                val now = System.nanoTime()
                val dt = (now - last) / 1e9f
                last = now
                game.onDrawFrame(dt)
            }
        })
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        keepScreenOn = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val scale = min(1f, 720f / min(w, h))
        surfW = (w * scale).toInt(); surfH = (h * scale).toInt()
        holder.setFixedSize(surfW, surfH)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val sx = surfW.toFloat() / maxOf(1, width); val sy = surfH.toFloat() / maxOf(1, height)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                game.onTouch(TouchEvent(TouchEvent.DOWN, e.getPointerId(i), e.getX(i) * sx, e.getY(i) * sy))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                game.onTouch(TouchEvent(TouchEvent.MOVE, e.getPointerId(i), e.getX(i) * sx, e.getY(i) * sy))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                game.onTouch(TouchEvent(TouchEvent.UP, e.getPointerId(i), e.getX(i) * sx, e.getY(i) * sy))
            }
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) {
                game.onTouch(TouchEvent(TouchEvent.CANCEL, e.getPointerId(i), e.getX(i) * sx, e.getY(i) * sy))
            }
        }
        return true
    }
}
