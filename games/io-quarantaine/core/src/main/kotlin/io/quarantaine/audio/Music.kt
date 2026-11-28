package io.quarantaine.audio

import io.quarantaine.math.Rng
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Musique générée en temps réel : basse distordue, batterie, lead et nappe.
 * Compositions originales, définies par des motifs de 16 pas.
 */
class Music(private val sr: Int) {

    class Track(
        val bpm: Int,
        val root: Int,
        /** Motif de basse : 16 pas, décalage en demi-tons ou null (silence). */
        val bass: Array<Int?>,
        /** Transposition par mesure (cycle). */
        val prog: IntArray,
        val kick: String,
        val snare: String,
        val hat: String,
        /** Arpège du lead (16 pas, décalages ou null) et mesures où il joue. */
        val lead: Array<Int?>,
        val leadBars: String,
        val pad: Boolean = false,
        val bassGate: Float = 0.8f,
        val minor: Boolean = true,
    )

    companion object {
        private val n: Int? = null
        private fun p(vararg v: Int?) = arrayOf(*v)

        val TRACKS = arrayOf(
            // 0 : menu — nappe sombre
            Track(76, 40, p(0, n, n, n, n, n, n, n, n, n, n, n, 7, n, n, n), intArrayOf(0, 0, -4, -4, -7, -7, -5, -5),
                "x.......x.......", "................", "....x.......x...",
                p(12, n, n, n, 15, n, n, n, 19, n, n, n, 15, n, n, n), "....xxxx", pad = true, bassGate = 0.95f),
            // 1 : secteur 1 — galop métal en mi
            Track(128, 40, p(0, n, 0, 0, 0, n, 0, 0, 3, n, 0, 0, 5, n, 3, n), intArrayOf(0, 0, 0, 0, -2, -2, 1, 1),
                "x...x...x.x.x...", "....x.......x...", "x.x.x.x.x.x.x.x.",
                p(12, n, 19, n, 24, n, 19, n, 27, n, 24, n, 19, n, 15, n), "....xxxx"),
            // 2 : secteur 2 — lourd et lent en ré
            Track(108, 38, p(0, n, n, 0, n, n, 0, n, 1, n, n, 0, n, n, -2, n), intArrayOf(0, 0, 3, 3, 0, 0, -2, -1),
                "x.....x...x.....", "........x.......", "x...x...x...x...",
                p(12, n, n, n, n, n, 13, n, 15, n, n, n, 12, n, n, n), "..xx..xx", bassGate = 0.9f),
            // 3 : secteur 3 — pulsation inquiétante en do#
            Track(138, 37, p(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0), intArrayOf(0, 0, 1, 1, 0, 0, -1, -1),
                "x...x...x...x...", "....x.......x...", "xxxxxxxxxxxxxxxx",
                p(12, n, 15, n, 18, n, 21, n, 24, n, 21, n, 18, n, 15, n), "..xx.xxx", bassGate = 0.55f),
            // 4 : boss — double pédale en si
            Track(160, 35, p(0, 0, 12, 0, 0, 0, 1, 0, 0, 0, 12, 0, 3, 0, 1, 0), intArrayOf(0, 0, -2, -2, 1, 1, -1, 3),
                "x.xxx.xxx.xxx.xx", "....x.......x...", "x.x.x.x.x.x.x.x.",
                p(24, n, 23, n, 19, n, 18, n, 24, n, 30, n, 27, n, 23, n), "xxxxxxxx", bassGate = 0.6f),
            // 5 : victoire — arpège lumineux
            Track(100, 40, p(0, n, n, n, 0, n, n, n, 0, n, n, n, 7, n, n, n), intArrayOf(0, 0, 5, 5, 7, 7, 0, 0),
                "x.......x.......", "....x.......x...", "x.x.x.x.x.x.x.x.",
                p(12, 16, 19, 24, 19, 16, 12, 16, 19, 24, 28, 24, 19, 16, 19, 24), "xxxxxxxx", pad = true, minor = false),
        )

        fun freq(midi: Float) = 440f * 2f.pow((midi - 69f) / 12f)
    }

    @Volatile var requested = -1
    private var track: Track? = null
    private var stepSamples = 1
    private var sampleInStep = 0
    private var step = 0
    private var bar = 0
    private val rng = Rng(77)

    // Voix de basse
    private var bassFreq = 0f; private var bassPh1 = 0f; private var bassPh2 = 0f; private var bassPhSub = 0f
    private var bassEnv = 0f; private var bassGateLeft = 0; private var bassFilt = 0f; private var bassFiltEnv = 0f
    private var svfLow = 0f; private var svfBand = 0f
    // Batterie
    private var kickT = 10f; private var kickPh = 0f
    private var snareT = 10f; private var hatT = 10f; private var hatOpen = false
    private var hatLp = 0f; private var snareLp = 0f
    // Lead + écho
    private var leadFreq = 0f; private var leadPh = 0f; private var leadT = 10f; private var leadOn = false
    private val delay = FloatArray(sr)
    private var delayPos = 0
    // Nappe
    private val padPh = FloatArray(6)
    private var padRoot = 0f
    private var padLp = 0f
    private var fade = 1f
    private val minorIv = intArrayOf(0, 3, 7)
    private val majorIv = intArrayOf(0, 4, 7)
    private val padFreq = FloatArray(3)
    private var padCachedRoot = -1f
    private var padCachedMinor = true

    private fun start(t: Track) {
        track = t
        stepSamples = (sr * 60f / t.bpm / 4f).toInt()
        sampleInStep = 0; step = 0; bar = 0
        triggerStep(t)
    }

    private fun triggerStep(t: Track) {
        val transpose = t.prog[bar % t.prog.size]
        t.bass[step]?.let {
            bassFreq = freq((t.root + transpose + it).toFloat())
            bassEnv = 1f; bassFiltEnv = 1f
            bassGateLeft = (stepSamples * t.bassGate * if (t.pad) 4f else 1f).toInt()
        }
        if (t.kick[step] == 'x') { kickT = 0f; kickPh = 0f }
        if (t.snare[step] == 'x') snareT = 0f
        if (t.hat[step] == 'x' || t.hat[step] == 'o') { hatT = 0f; hatOpen = t.hat[step] == 'o' }
        leadOn = t.leadBars[bar % t.leadBars.length] == 'x'
        if (leadOn) t.lead[step]?.let {
            val third = if (!t.minor && (it % 12 == 3 || it % 12 == 15)) 1 else 0
            leadFreq = freq((t.root + 12 + transpose + it + third).toFloat())
            leadT = 0f
        }
        if (step == 0) padRoot = (t.root + 12 + transpose).toFloat()
    }

    /** Ajoute la musique (stéréo entrelacée) dans [out]. */
    fun render(out: FloatArray, frames: Int, volume: Float) {
        val req = requested
        if (req >= 0 && (track == null || TRACKS.getOrNull(req) !== track)) {
            fade -= frames.toFloat() / (sr * 0.3f)
            if (fade <= 0f) { fade = 0f; start(TRACKS[req]) }
        } else if (req < 0) {
            fade -= frames.toFloat() / (sr * 0.3f)
            if (fade <= 0f) { fade = 0f; track = null }
        } else fade = minOf(1f, fade + frames.toFloat() / (sr * 0.5f))
        val t = track ?: return
        val dt = 1f / sr
        val twoPi = 2f * PI.toFloat()
        for (i in 0 until frames) {
            if (++sampleInStep >= stepSamples) {
                sampleInStep = 0
                step++
                if (step >= 16) { step = 0; bar++ }
                triggerStep(t)
            }
            // Basse : deux dents de scie désaccordées + sous-octave, filtre résonant
            var bass = 0f
            if (bassFreq > 0f) {
                bassPh1 += bassFreq * dt; bassPh2 += bassFreq * 1.006f * dt; bassPhSub += bassFreq * 0.5f * dt
                bassPh1 -= floor(bassPh1); bassPh2 -= floor(bassPh2); bassPhSub -= floor(bassPhSub)
                val raw = (bassPh1 * 2f - 1f) + (bassPh2 * 2f - 1f) * 0.8f + (if (bassPhSub < 0.5f) 0.5f else -0.5f)
                if (bassGateLeft > 0) bassGateLeft-- else bassEnv *= 0.9985f
                bassFiltEnv *= 0.9996f
                val cutoff = (if (t.pad) 220f else 260f) + 1600f * bassFiltEnv
                val f = 2f * sin(PI.toFloat() * minOf(cutoff, sr / 6f) / sr)
                svfLow += f * svfBand
                val high = raw - svfLow - 0.6f * svfBand
                svfBand += f * high
                bass = tanh(svfLow * 1.8f) * bassEnv * 0.42f
            }
            // Grosse caisse
            var drums = 0f
            if (kickT < 0.4f) {
                val kf = 45f + 110f * exp(-kickT * 35f)
                kickPh += kf * dt
                drums += sin(twoPi * kickPh) * exp(-kickT * 9f) * 0.9f
                kickT += dt
            }
            // Caisse claire
            if (snareT < 0.3f) {
                val nz = rng.float() * 2f - 1f
                snareLp += (nz - snareLp) * 0.35f
                drums += (nz - snareLp * 0.5f) * exp(-snareT * 18f) * 0.45f + sin(twoPi * 190f * snareT) * exp(-snareT * 30f) * 0.3f
                snareT += dt
            }
            // Charleston
            if (hatT < 0.2f) {
                val nz = rng.float() * 2f - 1f
                hatLp += (nz - hatLp) * 0.5f
                drums += (nz - hatLp) * exp(-hatT * if (hatOpen) 14f else 60f) * 0.16f
                hatT += dt
            }
            // Lead à impulsions + vibrato
            var lead = 0f
            if (leadT < 0.6f && leadFreq > 0f) {
                val vib = 1f + 0.006f * sin(twoPi * 5.5f * leadT) * minOf(1f, leadT * 5f)
                leadPh += leadFreq * vib * dt
                leadPh -= floor(leadPh)
                lead = (if (leadPh < 0.3f) 1f else -1f) * exp(-leadT * 4.5f) * 0.12f
                leadT += dt
            }
            // Nappe (menu / victoire)
            var pad = 0f
            if (t.pad) {
                val iv = if (t.minor) minorIv else majorIv
                if (padCachedRoot != padRoot || padCachedMinor != t.minor) {
                    for (k in 0 until 3) padFreq[k] = freq(padRoot + iv[k])
                    padCachedRoot = padRoot; padCachedMinor = t.minor
                }
                for (k in 0 until 3) {
                    val f = padFreq[k]
                    padPh[k * 2] += f * dt; padPh[k * 2 + 1] += f * 1.004f * dt
                    padPh[k * 2] -= floor(padPh[k * 2]); padPh[k * 2 + 1] -= floor(padPh[k * 2 + 1])
                    pad += (padPh[k * 2] * 2f - 1f) + (padPh[k * 2 + 1] * 2f - 1f)
                }
                padLp += (pad * 0.06f - padLp) * 0.02f
                pad = padLp
            }
            // Écho sur le lead
            val dLen = (stepSamples * 3).coerceAtMost(delay.size - 1)
            val readPos = (delayPos - dLen + delay.size) % delay.size
            val echo = delay[readPos]
            delay[delayPos] = lead + echo * 0.35f
            delayPos = (delayPos + 1) % delay.size
            val dry = bass + drums * 0.8f + pad
            val l = (dry + lead + echo * 0.5f) * volume * fade * 0.8f
            val r = (dry + lead * 0.8f + echo * 0.7f) * volume * fade * 0.8f
            out[i * 2] += l
            out[i * 2 + 1] += r
        }
    }
}
