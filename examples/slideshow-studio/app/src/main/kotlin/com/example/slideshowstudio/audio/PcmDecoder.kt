package com.example.slideshowstudio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Decodes a music file into raw PCM on disk: 16 bit, little endian, interleaved.
 *
 * Everything is normalised to one sample rate and one channel count here, so the mixer downstream
 * only ever deals with one kind of buffer. The result goes to a file rather than to memory: a few
 * minutes of stereo audio is tens of megabytes, and several tracks would not fit comfortably.
 */
object PcmDecoder {

    private const val TAG = "PcmDecoder"
    private const val TIMEOUT_US = 10_000L
    private const val BYTES_PER_SAMPLE = 2

    /**
     * @param maxSeconds stop once this much audio has been written; the rest of the file is of no
     *   use because the video is over by then.
     * @return the number of frames written, or 0 when the file could not be decoded.
     */
    fun decodeToFile(
        context: Context,
        uri: Uri,
        output: File,
        sampleRate: Int,
        channels: Int,
        maxSeconds: Float,
    ): Long {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var framesWritten = 0L
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return 0L

            val inputFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return 0L
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val maxFrames = (maxSeconds * sampleRate).toLong().coerceAtLeast(0L)
            var sourceRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var sourceChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val resampler = Resampler(sourceRate, sampleRate, sourceChannels, channels)

            BufferedOutputStream(output.outputStream()).use { sink ->
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone && framesWritten < maxFrames) {
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = decoder.getInputBuffer(inputIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = decoder.outputFormat
                            sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            resampler.reconfigure(sourceRate, sourceChannels)
                        }

                        else -> {
                            if (outputIndex >= 0) {
                                val buffer = decoder.getOutputBuffer(outputIndex)
                                if (buffer != null && info.size > 0) {
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    val remaining = maxFrames - framesWritten
                                    framesWritten += resampler.process(buffer, sink, remaining)
                                }
                                decoder.releaseOutputBuffer(outputIndex, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    outputDone = true
                                }
                            }
                        }
                    }
                }
            }
            return framesWritten
        } catch (error: Exception) {
            Log.w(TAG, "Impossible de décoder $uri", error)
            return framesWritten
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Converts the decoder's output to the target rate and channel count.
     *
     * Rate conversion is a linear interpolation, which is plenty for background music, and it keeps
     * its position between buffers so no click appears at buffer boundaries.
     */
    private class Resampler(
        private var sourceRate: Int,
        private val targetRate: Int,
        private var sourceChannels: Int,
        private val targetChannels: Int,
    ) {
        private var position = 0.0
        private var previousFrame = ShortArray(targetChannels)
        private var hasPrevious = false

        fun reconfigure(rate: Int, channels: Int) {
            sourceRate = rate
            sourceChannels = channels
        }

        fun process(buffer: ByteBuffer, sink: BufferedOutputStream, maxFrames: Long): Long {
            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            val frameCount = shorts.remaining() / sourceChannels.coerceAtLeast(1)
            if (frameCount <= 0) return 0L

            val step = sourceRate.toDouble() / targetRate
            val out = ByteArray(targetChannels * BYTES_PER_SAMPLE)
            var written = 0L
            val current = ShortArray(targetChannels)

            for (frame in 0 until frameCount) {
                readFrame(shorts, frame, current)
                if (!hasPrevious) {
                    previousFrame = current.copyOf()
                    hasPrevious = true
                }
                // Emit every output frame whose position falls inside this source frame.
                while (position < 1.0 && written < maxFrames) {
                    for (channel in 0 until targetChannels) {
                        val value = previousFrame[channel] + (current[channel] - previousFrame[channel]) * position
                        writeShort(out, channel, value.toInt().coerceIn(-32768, 32767).toShort())
                    }
                    sink.write(out)
                    written++
                    position += step
                }
                position -= 1.0
                previousFrame = current.copyOf()
                if (written >= maxFrames) break
            }
            return written
        }

        private fun readFrame(shorts: java.nio.ShortBuffer, frame: Int, out: ShortArray) {
            val base = frame * sourceChannels
            when {
                sourceChannels >= targetChannels ->
                    for (channel in 0 until targetChannels) {
                        out[channel] = shorts.get(base + min(channel, sourceChannels - 1))
                    }
                // Mono into stereo: the same signal on both sides.
                else -> {
                    val sample = shorts.get(base)
                    for (channel in 0 until targetChannels) out[channel] = sample
                }
            }
        }

        private fun writeShort(target: ByteArray, channel: Int, value: Short) {
            val offset = channel * BYTES_PER_SAMPLE
            target[offset] = (value.toInt() and 0xFF).toByte()
            target[offset + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
    }
}
