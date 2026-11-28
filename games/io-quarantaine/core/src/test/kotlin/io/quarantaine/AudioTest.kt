package io.quarantaine

import io.quarantaine.audio.Mixer
import io.quarantaine.audio.Music
import io.quarantaine.audio.Sfx
import io.quarantaine.audio.Synth
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class AudioTest {
    @Test
    fun allSoundEffectsAreAudibleAndFinite() {
        val synth = Synth(44100)
        for (id in 0 until Sfx.COUNT) {
            val s = synth.generate(id)
            assertTrue(s.size > 1000, "son $id trop court")
            assertTrue(s.all { it.isFinite() }, "son $id : valeur invalide")
            assertTrue(s.maxOf { abs(it) } > 0.5f, "son $id silencieux")
        }
    }

    @Test
    fun everyMusicTrackPlays() {
        for (t in Music.TRACKS.indices) {
            val m = Mixer(44100)
            m.prepare()
            m.setMusic(t)
            val buf = ShortArray(1024 * 2)
            var peak = 0
            repeat(44100 * 4 / 1024) {
                m.render(buf, 1024)
                for (v in buf) peak = maxOf(peak, abs(v.toInt()))
            }
            assertTrue(peak > 2000, "piste $t trop faible ($peak)")
        }
    }

    @Test
    fun mixerPlaysManyVoicesWithoutClippingToSilence() {
        val m = Mixer(44100)
        m.prepare()
        for (id in 0 until Sfx.COUNT) m.play(id, 1f, if (id % 2 == 0) -0.5f else 0.5f, 1f)
        val buf = ShortArray(2048)
        m.render(buf, 1024)
        assertTrue(buf.any { it.toInt() != 0 })
    }
}
