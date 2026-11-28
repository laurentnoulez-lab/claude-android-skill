package io.quarantaine.audio

import io.quarantaine.math.Rng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tanh

/** Synthèse des effets sonores (aucun échantillon externe). */
class Synth(private val sr: Int) {
    private val rng = Rng(4242)

    private fun buf(seconds: Float) = FloatArray((seconds * sr).toInt())
    private fun t(i: Int) = i.toFloat() / sr
    private fun env(time: Float, tau: Float, attack: Float = 0.002f): Float =
        if (time < attack) time / attack else exp(-(time - attack) / tau)

    /** Filtre passe-bas à un pôle (coefficient recalculé par échantillon). */
    private class Lp { var y = 0f; fun run(x: Float, cutoff: Float, sr: Int): Float { val a = 1f - exp(-2f * PI.toFloat() * cutoff / sr); y += (x - y) * a; return y } }

    private fun noise() = rng.float() * 2f - 1f

    private fun normalize(b: FloatArray, peak: Float = 0.9f): FloatArray {
        var m = 1e-6f
        for (v in b) m = max(m, abs(v))
        val k = peak / m
        for (i in b.indices) b[i] *= k
        // Fondu de sortie (évite les clics)
        val fade = minOf(b.size, sr / 200)
        for (i in 0 until fade) b[b.size - 1 - i] *= i.toFloat() / fade
        return b
    }

    private fun sweep(f0: Float, f1: Float, time: Float, dur: Float) = f0 + (f1 - f0) * (time / dur).coerceIn(0f, 1f)

    /** Coup de feu générique : bruit filtré + impact grave. */
    private fun gun(dur: Float, tau: Float, c0: Float, c1: Float, thumpF0: Float, thumpF1: Float, thumpAmt: Float): FloatArray {
        val b = buf(dur); val lp = Lp(); var ph = 0f
        for (i in b.indices) {
            val tt = t(i)
            val n = lp.run(noise(), sweep(c0, c1, tt, tau * 4f), sr) * env(tt, tau)
            ph += sweep(thumpF0, thumpF1, tt, 0.12f) / sr
            val th = sin(2f * PI.toFloat() * ph) * env(tt, tau * 1.3f) * thumpAmt
            b[i] = tanh((n * 1.6f + th) * 1.5f)
        }
        return b
    }

    private fun tone(dur: Float, wave: (Float) -> Float, f: (Float) -> Float, amp: (Float) -> Float): FloatArray {
        val b = buf(dur); var ph = 0f
        for (i in b.indices) {
            val tt = t(i)
            ph += f(tt) / sr
            b[i] = wave(ph - kotlin.math.floor(ph)) * amp(tt)
        }
        return b
    }

    private val square: (Float) -> Float = { p -> if (p < 0.5f) 1f else -1f }
    private val saw: (Float) -> Float = { p -> p * 2f - 1f }
    private val sine: (Float) -> Float = { p -> sin(p * 2f * PI.toFloat()) }

    private fun mix(vararg parts: FloatArray): FloatArray {
        val n = parts.maxOf { it.size }
        val out = FloatArray(n)
        for (p in parts) for (i in p.indices) out[i] += p[i]
        return out
    }

    private fun delayed(src: FloatArray, seconds: Float): FloatArray {
        val off = (seconds * sr).toInt()
        val out = FloatArray(src.size + off)
        System.arraycopy(src, 0, out, off, src.size)
        return out
    }

    /** Grognement de créature : dent de scie modulée + bruit, filtrée. */
    private fun growl(dur: Float, f0: Float, f1: Float, cutoff: Float, vib: Float, noiseAmt: Float, tau: Float): FloatArray {
        val b = buf(dur); val lp = Lp(); val lp2 = Lp(); var ph = 0f; var ph2 = 0f
        for (i in b.indices) {
            val tt = t(i)
            val f = sweep(f0, f1, tt, dur) * (1f + 0.04f * sin(2f * PI.toFloat() * vib * tt))
            ph += f / sr; ph2 += f * 1.013f / sr
            val s = (saw(ph - kotlin.math.floor(ph)) + saw(ph2 - kotlin.math.floor(ph2))) * 0.5f + noise() * noiseAmt
            val a = env(tt, tau, 0.03f) * (0.7f + 0.3f * sin(2f * PI.toFloat() * 7f * tt))
            b[i] = tanh(lp2.run(lp.run(s, cutoff, sr), cutoff * 1.5f, sr) * a * 3f)
        }
        return b
    }

    fun generate(id: Int): FloatArray = normalize(when (id) {
        Sfx.PISTOL -> gun(0.3f, 0.045f, 5000f, 500f, 160f, 60f, 0.6f)
        Sfx.ENEMY_RIFLE -> gun(0.3f, 0.05f, 3200f, 400f, 130f, 55f, 0.5f)
        Sfx.SHOTGUN -> {
            val shot = gun(0.5f, 0.09f, 3500f, 250f, 95f, 40f, 0.9f)
            val click = gun(0.05f, 0.006f, 7000f, 3000f, 900f, 700f, 0.2f)
            mix(shot, delayed(click.map { it * 0.35f }.toFloatArray(), 0.42f), delayed(click.map { it * 0.3f }.toFloatArray(), 0.52f))
        }
        Sfx.CHAINGUN -> gun(0.16f, 0.028f, 6000f, 900f, 190f, 90f, 0.5f)
        Sfx.ROCKET_FIRE -> {
            val b = buf(0.8f); val lp = Lp(); var ph = 0f
            for (i in b.indices) {
                val tt = t(i)
                val c = if (tt < 0.15f) sweep(500f, 2600f, tt, 0.15f) else sweep(2600f, 500f, tt - 0.15f, 0.6f)
                ph += 70f / sr
                b[i] = lp.run(noise(), c, sr) * env(tt, 0.25f, 0.02f) * 1.5f + sin(2f * PI.toFloat() * ph) * env(tt, 0.12f) * 0.6f
            }
            b
        }
        Sfx.EXPLOSION -> {
            val b = buf(1.5f); val lp = Lp(); var ph = 0f
            for (i in b.indices) {
                val tt = t(i)
                ph += sweep(60f, 28f, tt, 1.2f) / sr
                val n = lp.run(noise(), sweep(2600f, 120f, tt, 1.2f), sr) * env(tt, 0.35f, 0.004f)
                b[i] = tanh((n * 2.2f + sin(2f * PI.toFloat() * ph) * env(tt, 0.4f) * 0.9f) * 1.8f)
            }
            b
        }
        Sfx.ANNIHIL_CHARGE -> tone(0.42f, sine, { 200f + 1300f * (it / 0.42f) * (it / 0.42f) }, { 0.4f + 0.6f * (it / 0.42f) * (0.6f + 0.4f * sin(it * 120f)) })
        Sfx.ANNIHIL_FIRE -> {
            val b = buf(1.1f); var pc = 0f; var pm = 0f; val lp = Lp()
            for (i in b.indices) {
                val tt = t(i)
                pm += 310f / sr
                val idx = 6f * env(tt, 0.25f)
                pc += (110f + 60f * sin(2f * PI.toFloat() * pm) * idx) / sr
                b[i] = tanh(sin(2f * PI.toFloat() * pc + idx * sin(2f * PI.toFloat() * pm)) * env(tt, 0.35f) * 1.5f + lp.run(noise(), 1800f, sr) * env(tt, 0.12f) * 1.5f)
            }
            b
        }
        Sfx.PUNCH -> gun(0.2f, 0.03f, 1500f, 300f, 120f, 45f, 1.2f)
        Sfx.MELEE_HIT -> mix(gun(0.25f, 0.04f, 2500f, 400f, 110f, 50f, 0.9f), tone(0.12f, saw, { 900f - it * 3000f }, { env(it, 0.03f) * 0.3f }))
        Sfx.WHOOSH -> {
            val b = buf(0.28f); val lp = Lp(); val lp2 = Lp()
            for (i in b.indices) {
                val tt = t(i)
                val c = 400f + 1800f * sin(PI.toFloat() * tt / 0.28f)
                val n = noise()
                b[i] = (lp.run(n, c, sr) - lp2.run(n, c * 0.3f, sr)) * sin(PI.toFloat() * tt / 0.28f)
            }
            b
        }
        Sfx.ACID, Sfx.SPLAT -> {
            val dur = if (id == Sfx.ACID) 0.4f else 0.3f
            val b = buf(dur); val lp = Lp(); var ph = 0f
            for (i in b.indices) {
                val tt = t(i)
                ph += (180f + 120f * sin(tt * 90f)) / sr
                b[i] = lp.run(noise(), 900f + 500f * sin(tt * 60f), sr) * env(tt, 0.08f, 0.01f) * 1.5f + sin(2f * PI.toFloat() * ph) * env(tt, 0.1f) * 0.5f
            }
            b
        }
        Sfx.PLASMA -> tone(0.3f, square, { 1300f * exp(-it * 9f) + 250f }, { env(it, 0.09f) * 0.6f })
        Sfx.SIGHT_MINER -> growl(0.7f, 120f, 85f, 900f, 6f, 0.4f, 0.3f)
        Sfx.SIGHT_CRAWLER -> growl(0.5f, 650f, 1100f, 3500f, 30f, 0.3f, 0.18f)
        Sfx.SIGHT_SPITTER -> growl(0.8f, 170f, 120f, 1100f, 25f, 0.6f, 0.35f)
        Sfx.SIGHT_BRUTE -> growl(1.0f, 75f, 55f, 650f, 5f, 0.5f, 0.45f)
        Sfx.SIGHT_DRONE -> {
            val b = buf(0.6f)
            for (k in 0 until 3) {
                val s = tone(0.09f, square, { if (k % 2 == 0) 880f else 660f }, { env(it, 0.05f) * 0.5f })
                val off = (k * 0.15f * sr).toInt()
                for (i in s.indices) if (off + i < b.size) b[off + i] += s[i]
            }
            b
        }
        Sfx.BOSS_ROAR -> growl(1.9f, 52f, 38f, 520f, 8f, 0.7f, 0.9f)
        Sfx.PAIN -> growl(0.25f, 320f, 210f, 1600f, 20f, 0.5f, 0.1f)
        Sfx.DEATH -> growl(0.9f, 220f, 55f, 1100f, 9f, 0.6f, 0.3f)
        Sfx.BIG_DEATH -> growl(1.4f, 110f, 30f, 700f, 6f, 0.7f, 0.5f)
        Sfx.DOOR_OPEN, Sfx.DOOR_CLOSE -> {
            val b = buf(1.0f); val lp = Lp(); val hp = Lp(); var ph = 0f
            val closing = id == Sfx.DOOR_CLOSE
            for (i in b.indices) {
                val tt = t(i)
                ph += (if (closing) sweep(65f, 50f, tt, 0.8f) else sweep(50f, 65f, tt, 0.8f)) / sr
                val motor = lp.run(saw(ph - kotlin.math.floor(ph)), 320f, sr) * (if (tt < 0.8f) 0.6f else 0f)
                val n = noise()
                val hiss = (n - hp.run(n, 2000f, sr)) * env(tt, 0.15f) * 0.35f
                val clunk = if (tt > 0.78f) sin(2f * PI.toFloat() * 90f * (tt - 0.78f)) * env(tt - 0.78f, 0.05f) * (if (closing) 1.2f else 0.6f) else 0f
                b[i] = motor + hiss + clunk
            }
            b
        }
        Sfx.PICKUP -> mix(tone(0.07f, square, { 880f }, { 0.4f }), delayed(tone(0.09f, square, { 1320f }, { env(it, 0.05f) * 0.4f }), 0.06f))
        Sfx.PICKUP_WEAPON -> {
            val notes = floatArrayOf(523f, 659f, 784f, 1047f)
            mix(*Array(4) { k -> delayed(tone(0.18f, square, { notes[k] }, { env(it, 0.08f) * 0.4f }), k * 0.08f) })
        }
        Sfx.PICKUP_KEY -> mix(tone(0.6f, sine, { 1047f }, { env(it, 0.2f) }), tone(0.6f, sine, { 1568f }, { env(it, 0.15f) * 0.6f * (0.5f + 0.5f * sin(it * 60f)) }))
        Sfx.PLAYER_PAIN -> growl(0.3f, 170f, 110f, 900f, 12f, 0.4f, 0.12f)
        Sfx.PLAYER_DEATH -> growl(1.3f, 190f, 45f, 900f, 7f, 0.5f, 0.45f)
        Sfx.EXIT -> {
            val notes = floatArrayOf(392f, 523f, 659f, 784f)
            mix(*Array(4) { k -> delayed(tone(0.6f, square, { notes[k] }, { env(it, 0.25f) * 0.35f }), k * 0.1f) })
        }
        Sfx.SECRET -> {
            val notes = floatArrayOf(659f, 831f, 988f, 1319f)
            mix(*Array(4) { k -> delayed(tone(0.5f, sine, { notes[k] }, { env(it, 0.2f) * 0.5f }), k * 0.12f) })
        }
        Sfx.NO_AMMO -> gun(0.06f, 0.006f, 6000f, 3000f, 1500f, 1200f, 0.1f)
        Sfx.UI_CLICK -> tone(0.06f, square, { 1000f }, { env(it, 0.02f) * 0.4f })
        Sfx.LAVA -> {
            val b = buf(0.45f); val lp = Lp()
            for (i in b.indices) {
                val tt = t(i)
                val n = noise()
                val crackle = if (rng.chance(0.002f)) 4f else 0f
                b[i] = ((n - lp.run(n, 3000f, sr)) + crackle * n) * env(tt, 0.15f, 0.02f)
            }
            b
        }
        Sfx.RICOCHET -> mix(tone(0.35f, sine, { 2600f - it * 2200f }, { env(it, 0.07f) * 0.5f }), gun(0.04f, 0.004f, 8000f, 4000f, 2000f, 2000f, 0.1f))
        Sfx.DENIED -> mix(tone(0.12f, square, { 130f }, { 0.5f }), delayed(tone(0.15f, square, { 110f }, { env(it, 0.1f) * 0.5f }), 0.16f))
        else -> buf(0.05f)
    })
}
