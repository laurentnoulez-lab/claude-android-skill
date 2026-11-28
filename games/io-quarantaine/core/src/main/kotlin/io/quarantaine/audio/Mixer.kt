package io.quarantaine.audio

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.tanh

/**
 * Mélangeur audio : effets sonores positionnés (stéréo) + musique.
 * [play] est appelé depuis le fil du jeu ; [render] depuis le fil audio de la plateforme.
 */
class Mixer(val sampleRate: Int) {
    private class Cmd(val id: Int, val volL: Float, val volR: Float, val rate: Float)
    private class Voice { var data: FloatArray? = null; var pos = 0f; var rate = 1f; var volL = 0f; var volR = 0f; var age = 0 }

    private val queue = ConcurrentLinkedQueue<Cmd>()
    private val voices = Array(28) { Voice() }
    @Volatile private var sounds: Array<FloatArray>? = null
    private val music = Music(sampleRate)
    private var mixBuf = FloatArray(0)

    @Volatile var sfxVolume = 0.9f
    @Volatile var musicVolume = 0.55f
    @Volatile var paused = false

    /** Génère tous les sons (à appeler depuis le fil audio avant le premier rendu). */
    fun prepare() {
        if (sounds != null) return
        val synth = Synth(sampleRate)
        sounds = Array(Sfx.COUNT) { synth.generate(it) }
    }

    val ready get() = sounds != null

    fun play(id: Int, volume: Float = 1f, pan: Float = 0f, pitch: Float = 1f) {
        if (volume <= 0.01f) return
        val p = pan.coerceIn(-1f, 1f)
        queue.add(Cmd(id, volume * (1f - maxOf(0f, p) * 0.7f), volume * (1f + minOf(0f, p) * 0.7f), pitch))
        while (queue.size > 64) queue.poll()
    }

    fun setMusic(track: Int) { music.requested = track }

    /** Remplit [out] (stéréo entrelacé, 16 bits) avec [frames] trames. */
    fun render(out: ShortArray, frames: Int) {
        if (mixBuf.size < frames * 2) mixBuf = FloatArray(frames * 2)
        val buf = mixBuf
        java.util.Arrays.fill(buf, 0, frames * 2, 0f)
        val snd = sounds
        while (true) {
            val c = queue.poll() ?: break
            if (snd == null) continue
            // Voix libre ou la plus ancienne
            var v = voices.firstOrNull { it.data == null }
            if (v == null) v = voices.maxByOrNull { it.age }!!
            v.data = snd[c.id]; v.pos = 0f; v.rate = c.rate; v.volL = c.volL; v.volR = c.volR; v.age = 0
        }
        if (!paused) {
            val sv = sfxVolume
            for (v in voices) {
                val d = v.data ?: continue
                v.age++
                var pos = v.pos
                val rate = v.rate
                val l = v.volL * sv; val r = v.volR * sv
                var i = 0
                while (i < frames) {
                    val ip = pos.toInt()
                    if (ip >= d.size - 1) { v.data = null; break }
                    val f = pos - ip
                    val s = d[ip] + (d[ip + 1] - d[ip]) * f
                    buf[i * 2] += s * l
                    buf[i * 2 + 1] += s * r
                    pos += rate
                    i++
                }
                v.pos = pos
            }
            music.render(buf, frames, musicVolume)
        }
        for (i in 0 until frames * 2) {
            val x = tanh(buf[i] * 0.8f)
            out[i] = (x * 32000f).toInt().toShort()
        }
    }
}
