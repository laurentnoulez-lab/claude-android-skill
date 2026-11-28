package io.quarantaine.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import io.quarantaine.audio.Mixer

/** Fil audio : synthétise et pousse le son du mélangeur vers AudioTrack. */
class AudioThread(private val mixer: Mixer) : Thread("io-audio") {
    @Volatile private var running = true

    override fun run() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            mixer.prepare()
            val sr = mixer.sampleRate
            val frames = 480
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minBuf, frames * 4 * 3))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track.play()
            val buf = ShortArray(frames * 2)
            while (running) {
                mixer.render(buf, frames)
                track.write(buf, 0, buf.size)
            }
            track.pause(); track.flush(); track.release()
        } catch (e: Throwable) {
            Log.e("IOQuarantaine", "Audio indisponible", e)
        }
    }

    fun shutdown() {
        running = false
        try { join(600) } catch (_: InterruptedException) {}
    }
}
