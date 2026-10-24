package com.example.readalong.data.transcription

import android.content.Context
import android.media.*
import android.net.Uri
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.example.readalong.domain.audio.PcmResampler
import kotlin.coroutines.coroutineContext

/** Decodes only a bounded interval to 16 kHz mono float PCM, without playing or copying the audiobook. */
class AudioDecoder(private val context: Context) {
    // Codec preparation stays serialized while the foreground owner processes one book.
    companion object { private val decoding = kotlinx.coroutines.sync.Semaphore(1) }
    suspend fun decode(uri: Uri, startMs: Long, durationMs: Long, destination: File): Long = decoding.withPermit {
        decodeInterval(uri, startMs, durationMs, destination)
    }
    private suspend fun decodeInterval(uri: Uri, startMs: Long, durationMs: Long, destination: File): Long {
        require(startMs >= 0 && durationMs in 1..30_000)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var mp3: Mp3PacketSource? = null
        var mp4: Mp4PacketSource? = null
        var aac: IndexedAacPacketSource? = null
        var decodedEndUs = Long.MIN_VALUE
        var firstDecodedUs = Long.MAX_VALUE
        var beforeEndUs = Long.MIN_VALUE
        var afterStartUs = Long.MAX_VALUE
        var reachedEof = false
        val samples = FloatArray((durationMs * 16).toInt())
        var written = 0
        var resampler: PcmResampler? = null
        var sampleRate = 0
        var channels = 0
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val fromUs = startMs * 1000
        val untilUs = fromUs + durationMs * 1000
        fun format(format: MediaFormat) {
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(channels in 1..8)
            encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            if (rate != sampleRate) {
                check(written == 0) { "Audio sample rate changed within a chunk." }
                sampleRate = rate
                resampler = PcmResampler(rate) { if (written < samples.size) samples[written++] = it.coerceIn(-1f, 1f) }
            }
        }
        fun consume(data: ByteBuffer, timeUs: Long) {
            data.order(ByteOrder.LITTLE_ENDIAN)
            val width = when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> 2
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                AudioFormat.ENCODING_PCM_32BIT -> 4
                else -> error("Unsupported decoded audio format.")
            }
            var frame = 0L
            while (data.remaining() >= width * channels) {
                var mono = 0f
                repeat(channels) {
                    mono += when (encoding) {
                        AudioFormat.ENCODING_PCM_16BIT -> data.short / 32768f
                        AudioFormat.ENCODING_PCM_FLOAT -> data.float
                        AudioFormat.ENCODING_PCM_8BIT -> ((data.get().toInt() and 255) - 128) / 128f
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                            val bits = (data.get().toInt() and 255) or ((data.get().toInt() and 255) shl 8) or (data.get().toInt() shl 16)
                            bits / 8388608f
                        }
                        else -> data.int / 2147483648f
                    }
                }
                val timestamp = timeUs + frame * 1_000_000 / sampleRate
                if (timestamp >= fromUs && timestamp < untilUs) resampler!!.sample(mono / channels)
                frame++
            }
            if (frame > 0) {
                val end = timeUs + frame * 1_000_000 / sampleRate
                decodedEndUs = maxOf(decodedEndUs, end)
                firstDecodedUs = minOf(firstDecodedUs, timeUs)
                if (end <= fromUs) beforeEndUs = maxOf(beforeEndUs, end)
                if (timeUs >= untilUs) afterStartUs = minOf(afterStartUs, timeUs)
            }
        }
        try {
            val mp4Container = context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(12)
                var count = 0
                while (count < header.size) { val n = stream.read(header, count, header.size-count); if (n < 0) break; count += n }
                count >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
            } == true
            fun platformInput(): MediaFormat {
                extractor.setDataSource(context, uri, null)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("No supported audio track was found.")
                extractor.selectTrack(track)
                extractor.seekTo((fromUs - 1_000_000).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                return extractor.getTrackFormat(track)
            }
            val input = if (mp4Container) {
                try {
                    aac = IndexedAacPacketSource(context, uri)
                    aac.open(fromUs)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) {
                    runCatching { aac?.close() }; aac = null
                    try {
                        mp4 = Mp4PacketSource(context, uri)
                        mp4.open(fromUs)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        // Preserve support for fragmented MP4 and non-AAC variants.
                        runCatching { mp4?.close() }; mp4 = null
                        platformInput()
                    }
                }
            } else platformInput()
            format(input)
            val mime = requireNotNull(input.getString(MediaFormat.KEY_MIME))
            if (mime == "audio/mpeg") {
                mp3 = Mp3PacketSource(context, uri)
                mp3.open(fromUs)
            }
            if (mime == "audio/raw") {
                val data = ByteBuffer.allocate(256 * 1024)
                while (written < samples.size) {
                    coroutineContext.ensureActive()
                    data.clear()
                    val size = extractor.readSampleData(data, 0)
                    if (size < 0) { reachedEof = true; break }
                    if (extractor.sampleTime >= untilUs) break
                    data.position(0); data.limit(size)
                    consume(data, extractor.sampleTime)
                    if (!extractor.advance()) { reachedEof = true; break }
                }
            } else {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(input, null, null, 0); codec.start()
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                var idle = 0
                while (!outputEnded && written < samples.size) {
                    coroutineContext.ensureActive()
                    var advanced = false
                    if (!inputEnded) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = requireNotNull(codec.getInputBuffer(index))
                            val packet = mp3?.next()
                            val mp4Packet = mp4?.next()
                            val aacPacket = aac?.next()
                            val time = if (aac != null) aacPacket?.timeUs ?: untilUs else if (mp3 != null) packet?.timeUs ?: untilUs else if (mp4 != null) mp4Packet?.timeUs ?: untilUs else extractor.sampleTime
                            // AAC edit lists may expose valid priming packets with negative PTS.
                            // Only readSampleData's -1 means EOF; a negative timestamp does not.
                            val size = if (time >= untilUs + 500_000) -1
                                else if (aac != null) aacPacket?.bytes?.let { buffer.put(it); it.size } ?: -1
                                else if (mp3 != null) packet?.bytes?.let { buffer.put(it); it.size } ?: -1
                                else if (mp4 != null) mp4Packet?.bytes?.let { buffer.put(it); it.size } ?: -1
                                else extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                reachedEof = time < untilUs + 500_000
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                            else { codec.queueInputBuffer(index, 0, size, time, 0); if (mp3 == null && mp4 == null && aac == null) extractor.advance() }
                            advanced = true
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { format(codec.outputFormat); advanced = true }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                consume(buffer, info.presentationTimeUs)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs >= untilUs
                            codec.releaseOutputBuffer(index, false); advanced = true
                        }
                    }
                    idle = if (advanced) 0 else idle + 1
                    check(idle < 1500) { "Audio decoding stalled. Try another file." }
                }
            }
            // Codec/edit-list priming can leave a tiny leading or bounded presentation gap.
            // Skip only an interval proven to lie outside all decoded samples, never a whole missing chunk.
            val leadingGap = fromUs == 0L && firstDecodedUs in 1..250_000 && untilUs <= firstDecodedUs
            val boundedGap = beforeEndUs != Long.MIN_VALUE && afterStartUs != Long.MAX_VALUE &&
                fromUs >= beforeEndUs && untilUs <= afterStartUs && afterStartUs - beforeEndUs in 1..250_000
            if (written == 0 && durationMs <= 250 && (leadingGap || boundedGap)) {
                destination.writeBytes(byteArrayOf())
                return 0
            }
            // Metadata durations can round a few milliseconds beyond the last decoded frame.
            // Only a proven tiny EOF tail can be empty; never silently skip a missing chapter.
            if (written == 0 && reachedEof && decodedEndUs != Long.MIN_VALUE &&
                fromUs >= decodedEndUs - 2_000 && untilUs - decodedEndUs in 0..250_000) {
                destination.writeBytes(byteArrayOf())
                return 0
            }
            check(written > 0) { "No audio was decoded in this interval." }
            destination.outputStream().buffered().use { output ->
                val buffer = ByteBuffer.allocate(16 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                for (index in 0 until written) {
                    buffer.putFloat(samples[index])
                    if (!buffer.hasRemaining()) { output.write(buffer.array()); buffer.clear() }
                }
                if (buffer.position() > 0) output.write(buffer.array(), 0, buffer.position())
            }
            return written * 1000L / 16000
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { extractor.release() }
            runCatching { mp3?.close() }; runCatching { mp4?.close() }; runCatching { aac?.close() }
        }
    }
}
