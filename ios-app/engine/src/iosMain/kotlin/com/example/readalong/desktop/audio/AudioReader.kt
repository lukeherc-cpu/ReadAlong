package com.example.readalong.desktop.audio

import com.example.readalong.compat.*
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlinx.cinterop.*
import platform.AVFoundation.*
import platform.AVFAudio.*
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.*
import platform.Foundation.NSNumber
import platform.Foundation.NSURL

/**
 * Decoding of any format iOS reads (M4B, M4A, AAC, MP3, FLAC, WAV, ALAC) through AVAssetReader, resampled to
 * [rate] Hz and [channels] channels: the desktop's FFmpeg AudioReader API, for the transcriber.
 */
class AudioReader(private val file: File, val rate: Int, val channels: Int, private val floats: Boolean = false) : Closeable {
    private val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(file.absolutePath), options = null)
    private var reader: AVAssetReader? = null
    private var output: AVAssetReaderTrackOutput? = null

    /** Position, in ms from the start of the file, of the next sample [read] returns. */
    var positionMs = 0L
        private set

    val durationMs: Long get() = (CMTimeGetSeconds(asset.duration) * 1000).let { if (it.isNaN()) 0L else it.toLong() }

    init { open(0) }

    private fun open(fromMs: Long) {
        reader?.cancelReading()
        val track = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack ?: throw IOException("This file has no audio iOS can read.")
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to NSNumber(unsignedInt = kAudioFormatLinearPCM),
            AVSampleRateKey to NSNumber(double = rate.toDouble()),
            AVNumberOfChannelsKey to NSNumber(int = channels),
            AVLinearPCMBitDepthKey to NSNumber(int = if (floats) 32 else 16),
            AVLinearPCMIsFloatKey to NSNumber(bool = floats),
            AVLinearPCMIsBigEndianKey to NSNumber(bool = false),
            AVLinearPCMIsNonInterleaved to NSNumber(bool = false),
        )
        val r = memScoped {
            val error = alloc<ObjCObjectVar<platform.Foundation.NSError?>>()
            AVAssetReader(asset = asset, error = error.ptr) ?: throw IOException(error.value?.localizedDescription ?: "This audio couldn't be opened.")
        }
        val out = AVAssetReaderTrackOutput(track = track, outputSettings = settings)
        out.alwaysCopiesSampleData = false
        r.addOutput(out)
        if (fromMs > 0) r.timeRange = CMTimeRangeMake(CMTimeMake(fromMs, 1000), kCMTimePositiveInfinity.readValue())
        if (!r.startReading()) throw IOException(r.error?.localizedDescription ?: "This audio couldn't be decoded.")
        reader = r; output = out
        positionMs = fromMs
    }

    fun seek(ms: Long) = open(ms.coerceAtLeast(0))

    /** The next decoded block as raw little-endian PCM bytes, or null at the end of the file. */
    private fun nextBytes(): ByteArray? {
        while (true) {
            val sample = output?.copyNextSampleBuffer() ?: return null
            try {
                val block = CMSampleBufferGetDataBuffer(sample) ?: continue
                val length = CMBlockBufferGetDataLength(block).toInt()
                if (length == 0) continue
                val bytes = ByteArray(length)
                bytes.usePinned { CMBlockBufferCopyDataBytes(block, 0u, length.convert(), it.addressOf(0)) }
                return bytes
            } finally { CFRelease(sample) }
        }
    }

    /** Interleaved 16-bit samples, or null at the end of the file. */
    fun readShorts(): ShortArray? {
        check(!floats)
        val bytes = nextBytes() ?: return null
        val out = ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
        positionMs += out.size / channels * 1000L / rate
        return out
    }

    /** Interleaved float samples, or null at the end of the file. */
    fun readFloats(): FloatArray? {
        check(floats)
        val bytes = nextBytes() ?: return null
        val out = FloatArray(bytes.size / 4)
        out.usePinned { o -> bytes.usePinned { b -> platform.posix.memcpy(o.addressOf(0), b.addressOf(0), (out.size * 4).convert()) } }
        positionMs += out.size / channels * 1000L / rate
        return out
    }

    override fun close() { reader?.cancelReading(); reader = null; output = null }
}
