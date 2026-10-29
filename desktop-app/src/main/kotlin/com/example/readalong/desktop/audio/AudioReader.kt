package com.example.readalong.desktop.audio

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** FFmpeg decoding of any audiobook format, resampled to [rate] Hz and [channels] channels. */
class AudioReader(file: File, val rate: Int, val channels: Int, private val floats: Boolean = false) : Closeable {
    private val grabber = FFmpegFrameGrabber(file).apply {
        sampleRate = rate
        audioChannels = channels
        sampleFormat = if (floats) avutil.AV_SAMPLE_FMT_FLT else avutil.AV_SAMPLE_FMT_S16
        start()
    }
    /** Samples per channel still to drop after a seek, so reading starts exactly where asked. */
    private var skip = 0L
    private var seekTargetUs = -1L
    /** Position, in ms from the start of the file, of the next sample [read] returns. */
    var positionMs = 0L
        private set

    val durationMs: Long get() = grabber.lengthInTime / 1000

    fun seek(ms: Long) {
        grabber.setAudioTimestamp(ms.coerceAtLeast(0) * 1000)
        seekTargetUs = ms.coerceAtLeast(0) * 1000
        positionMs = ms
    }

    /** Interleaved 16-bit samples, or null at the end of the file. */
    fun readShorts(): ShortArray? {
        check(!floats)
        while (true) {
            val frame = grabber.grabSamples() ?: return null
            val buffer = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
            val data = ShortArray(buffer.remaining()).also { buffer.get(it) }
            trim(frame.timestamp, data.size / channels)?.let { from -> return if (from == 0) data else data.copyOfRange(from * channels, data.size) }
        }
    }

    /** Interleaved float samples, or null at the end of the file. */
    fun readFloats(): FloatArray? {
        check(floats)
        while (true) {
            val frame = grabber.grabSamples() ?: return null
            val buffer = frame.samples?.firstOrNull() as? FloatBuffer ?: continue
            val data = FloatArray(buffer.remaining()).also { buffer.get(it) }
            trim(frame.timestamp, data.size / channels)?.let { from -> return if (from == 0) data else data.copyOfRange(from * channels, data.size) }
        }
    }

    /** After a seek FFmpeg resumes at the packet before the target; returns the first sample to keep, or null to skip the frame. */
    private fun trim(timestampUs: Long, count: Int): Int? {
        var from = 0
        if (seekTargetUs >= 0) {
            if (timestampUs in 0 until seekTargetUs) skip = (seekTargetUs - timestampUs) * rate / 1_000_000
            seekTargetUs = -1
        }
        if (skip > 0) {
            if (skip >= count) { skip -= count; return null }
            from = skip.toInt(); skip = 0
        }
        positionMs += (count - from) * 1000L / rate
        return from
    }

    override fun close() { runCatching { grabber.stop() }; runCatching { grabber.release() } }

    companion object {
        init { avutil.av_log_set_level(avutil.AV_LOG_ERROR) }
    }
}
