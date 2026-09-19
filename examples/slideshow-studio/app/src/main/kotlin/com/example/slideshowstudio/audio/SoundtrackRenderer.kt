package com.example.slideshowstudio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.example.slideshowstudio.engine.Soundtrack
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Turns a planned [Soundtrack] into an AAC file the length of the video.
 *
 * Each track is decoded to PCM on disk first, then the segments are mixed block by block following
 * the envelopes the engine computed: where two segments overlap, both are read and summed, which is
 * exactly what a crossfade is.
 */
class SoundtrackRenderer(
    private val context: Context,
    private val sampleRate: Int = SAMPLE_RATE,
    private val channels: Int = CHANNELS,
) {

    /**
     * @return the rendered audio file, or null when there is nothing to render or the device could
     *   not decode the music. A silent video is a far better outcome than a failed export.
     */
    fun render(
        soundtrack: Soundtrack,
        tracks: List<AudioTrackInfo>,
        workingDirectory: File,
        onProgress: (Float) -> Unit = {},
    ): File? {
        if (soundtrack.isEmpty) return null
        val pcmFiles = mutableMapOf<Int, File>()
        val output = File(workingDirectory, "soundtrack-${System.currentTimeMillis()}.m4a")
        try {
            // Only the tracks that actually made it onto the timeline are worth decoding.
            soundtrack.segments.map { it.trackIndex }.distinct().forEach { trackIndex ->
                val track = tracks.getOrNull(trackIndex) ?: return@forEach
                val needed = soundtrack.segments
                    .filter { it.trackIndex == trackIndex }
                    .maxOf { it.durationSeconds }
                val pcm = File(workingDirectory, "track-$trackIndex.pcm")
                val frames = PcmDecoder.decodeToFile(
                    context = context,
                    uri = track.uri,
                    output = pcm,
                    sampleRate = sampleRate,
                    channels = channels,
                    maxSeconds = needed + 1f,
                )
                if (frames > 0) pcmFiles[trackIndex] = pcm else pcm.delete()
            }
            if (pcmFiles.isEmpty()) return null

            return mix(soundtrack, pcmFiles, output, onProgress)
        } catch (error: Exception) {
            Log.w(TAG, "Impossible de préparer la bande sonore", error)
            output.delete()
            return null
        } finally {
            pcmFiles.values.forEach { it.delete() }
        }
    }

    private fun mix(
        soundtrack: Soundtrack,
        pcmFiles: Map<Int, File>,
        output: File,
        onProgress: (Float) -> Unit,
    ): File? {
        val totalFrames = (soundtrack.coveredSeconds * sampleRate).roundToLong()
        if (totalFrames <= 0L) return null

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BLOCK_FRAMES * channels * 2 * 2)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sources = pcmFiles.mapValues { (_, file) -> RandomAccessFile(file, "r") }
        var trackIndex = -1
        var muxerStarted = false
        var success = false

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val info = MediaCodec.BufferInfo()
            val mixBuffer = FloatArray(BLOCK_FRAMES * channels)
            val scratch = ByteArray(BLOCK_FRAMES * channels * 2)
            val readBuffer = ByteArray(BLOCK_FRAMES * channels * 2)
            var framesDone = 0L
            var inputDone = false

            while (true) {
                if (!inputDone) {
                    val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = encoder.getInputBuffer(inputIndex)!!
                        val frames = minOf(BLOCK_FRAMES.toLong(), totalFrames - framesDone).toInt()
                        if (frames <= 0) {
                            encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                framesDone * 1_000_000L / sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val bytes = fillBlock(
                                soundtrack = soundtrack,
                                sources = sources,
                                startFrame = framesDone,
                                frames = frames,
                                mixBuffer = mixBuffer,
                                readBuffer = readBuffer,
                                scratch = scratch,
                            )
                            buffer.clear()
                            buffer.put(scratch, 0, bytes)
                            encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                bytes,
                                framesDone * 1_000_000L / sampleRate,
                                0,
                            )
                            framesDone += frames
                            onProgress(framesDone.toFloat() / totalFrames)
                        }
                    }
                }

                val outputIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outputIndex >= 0) {
                    val encoded = encoder.getOutputBuffer(outputIndex)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && encoded != null && muxerStarted) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, encoded, info)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            success = muxerStarted
            return if (success) output else null
        } catch (error: Exception) {
            Log.w(TAG, "Impossible d'encoder la bande sonore", error)
            return null
        } finally {
            sources.values.forEach { runCatching { it.close() } }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            if (!success) output.delete()
        }
    }

    /**
     * Sums every segment playing during this block, each through its own volume envelope, and
     * writes the result as 16 bit samples.
     */
    private fun fillBlock(
        soundtrack: Soundtrack,
        sources: Map<Int, RandomAccessFile>,
        startFrame: Long,
        frames: Int,
        mixBuffer: FloatArray,
        readBuffer: ByteArray,
        scratch: ByteArray,
    ): Int {
        val samples = frames * channels
        java.util.Arrays.fill(mixBuffer, 0, samples, 0f)
        val blockStartSeconds = startFrame.toFloat() / sampleRate
        val blockEndSeconds = blockStartSeconds + frames.toFloat() / sampleRate

        soundtrack.segments.forEach { segment ->
            if (segment.endSeconds <= blockStartSeconds || segment.startSeconds >= blockEndSeconds) return@forEach
            val source = sources[segment.trackIndex] ?: return@forEach

            // Where this block falls inside the track. A segment that starts partway through the
            // block is read from its own first sample and placed at the right offset, so its attack
            // is never lost to the block grid.
            val segmentStartFrame = (segment.startSeconds * sampleRate).roundToLong()
            val skippedFrames = (segmentStartFrame - startFrame).coerceAtLeast(0L).toInt()
            if (skippedFrames >= frames) return@forEach
            val available = readFrom(
                source = source,
                frameOffset = (startFrame - segmentStartFrame).coerceAtLeast(0L),
                target = readBuffer,
                offsetFrames = skippedFrames,
                length = (frames - skippedFrames) * channels * 2,
            )

            for (frame in skippedFrames until frames) {
                val byteBase = frame * channels * 2
                if (byteBase + channels * 2 > available) break
                val gain = segment.gainAt(blockStartSeconds + frame.toFloat() / sampleRate) * soundtrack.volume
                if (gain <= 0f) continue
                for (channel in 0 until channels) {
                    val offset = byteBase + channel * 2
                    val sample = ((readBuffer[offset].toInt() and 0xFF) or (readBuffer[offset + 1].toInt() shl 8))
                        .toShort()
                    mixBuffer[frame * channels + channel] += sample * gain
                }
            }
        }

        for (index in 0 until samples) {
            // Summed tracks can exceed full scale; clipping here is the last resort, and the equal
            // power envelopes are what keep it from happening in practice.
            val value = mixBuffer[index].roundToInt().coerceIn(-32768, 32767)
            scratch[index * 2] = (value and 0xFF).toByte()
            scratch[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return samples * 2
    }

    /**
     * Reads [length] bytes of the track starting at [frameOffset] into [target], placed after
     * [offsetFrames] frames of silence. Anything past the end of the track reads as silence too.
     *
     * @return the number of bytes of [target] that hold meaningful audio.
     */
    private fun readFrom(
        source: RandomAccessFile,
        frameOffset: Long,
        target: ByteArray,
        offsetFrames: Int,
        length: Int,
    ): Int {
        val offsetBytes = offsetFrames * channels * 2
        java.util.Arrays.fill(target, 0, offsetBytes + length, 0)
        val bytePosition = frameOffset * channels * 2
        if (bytePosition >= source.length()) return 0
        source.seek(bytePosition)
        val read = source.read(target, offsetBytes, length)
        return if (read < 0) 0 else offsetBytes + read
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        private const val BIT_RATE = 192_000
        private const val BLOCK_FRAMES = 2048
        private const val TIMEOUT_US = 10_000L
        private const val TAG = "SoundtrackRenderer"
    }
}
