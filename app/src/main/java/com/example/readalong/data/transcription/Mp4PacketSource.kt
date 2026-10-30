package com.example.readalong.data.transcription

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.Mp4Extractor
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.EOFException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/** Playback-compatible MP4 sample tables. Some valid long M4Bs expose zero platform tracks.
 * No audio copy/remux is required; only a bounded compressed sample is buffered. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class Mp4PacketSource(context: Context, private val uri: Uri) : Closeable {
    data class Packet(val bytes: ByteArray, val timeUs: Long)
    private val data = DefaultDataSource.Factory(context).createDataSource()
    private val extractor = Mp4Extractor()
    private lateinit var input: DefaultExtractorInput
    private var seekMap: SeekMap? = null
    private var sourceFormat: Format? = null
    private var audioTrack: Int? = null
    private var packet: Packet? = null
    private var pending = ByteArray(16 * 1024)
    private var pendingSize = 0
    private var ended = false
    private val output = object : TrackOutput {
        private fun capacity(length: Int) {
            require(length >= 0 && pendingSize + length <= 2 * 1024 * 1024) { "Audio packet is too large." }
            if (pendingSize + length > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + length))
        }
        override fun format(format: Format) { sourceFormat = format }
        override fun sampleData(reader: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            capacity(length)
            val count = reader.read(pending, pendingSize, length)
            if (count < 0) { if (!allowEndOfInput) throw EOFException(); return C.RESULT_END_OF_INPUT }
            pendingSize += count
            return count
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            capacity(length); data.readBytes(pending, pendingSize, length); pendingSize += length
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            require(cryptoData == null) { "This audio is copy-protected (DRM), so ReadAlong can't make text from it. Import it with LibriSync instead." }
            check(size <= pendingSize && offset <= pendingSize - size)
            val start = pendingSize - offset - size
            packet = Packet(pending.copyOfRange(start, start + size), timeUs)
            if (offset > 0) pending.copyInto(pending, 0, pendingSize - offset, pendingSize)
            pendingSize = offset
        }
    }
    suspend fun open(startUs: Long): MediaFormat {
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                if (type == C.TRACK_TYPE_AUDIO && (audioTrack == null || audioTrack == id)) { audioTrack = id; return output }
                return DiscardingTrackOutput()
            }
            override fun endTracks() = Unit
            override fun seekMap(value: SeekMap) { seekMap = value }
        })
        reopen(0)
        while (sourceFormat == null || seekMap == null) {
            coroutineContext.ensureActive()
            check(read() != Extractor.RESULT_END_OF_INPUT) { "No supported MP4 audio track was found." }
        }
        val source = requireNotNull(sourceFormat)
        require(source.drmInitData == null) { "This audio is copy-protected (DRM), so ReadAlong can't make text from it. Import it with LibriSync instead." }
        val format = MediaFormat.createAudioFormat(requireNotNull(source.sampleMimeType), source.sampleRate, source.channelCount)
        source.initializationData.forEachIndexed { index, bytes -> format.setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(64 * 1024, source.maxInputSize))
        val target = (startUs - 1_000_000).coerceAtLeast(0)
        val point = requireNotNull(seekMap).getSeekPoints(target).first
        packet = null; pendingSize = 0
        reopen(point.position); extractor.seek(point.position, target)
        return format
    }
    private fun reopen(position: Long) {
        data.close()
        val length = data.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
        input = DefaultExtractorInput(data, position, if (length == C.LENGTH_UNSET.toLong()) length else position + length)
    }
    private fun read(): Int {
        val position = PositionHolder()
        val result = extractor.read(input, position)
        if (result == Extractor.RESULT_SEEK) reopen(position.position)
        return result
    }
    suspend fun next(): Packet? {
        while (packet == null && !ended) { coroutineContext.ensureActive(); ended = read() == Extractor.RESULT_END_OF_INPUT }
        return packet.also { packet = null }
    }
    override fun close() { try { data.close() } finally { extractor.release() } }
}
