package com.example.readalong.data.transcription

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.*
import androidx.media3.extractor.mp3.Mp3Extractor
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.EOFException
import kotlin.coroutines.coroutineContext

/** Uses the same MP3 seek map as playback. Android's MediaExtractor can use the coarse
 * Info-header table even for CBR files, assigning speech a timestamp seconds away from it.
 * Only one compressed frame is buffered; a short pre-roll restores the MP3 bit reservoir. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class Mp3PacketSource(context: Context, private val uri: Uri) : Closeable {
    data class Packet(val bytes: ByteArray, val timeUs: Long)
    private val data = DefaultDataSource.Factory(context).createDataSource()
    private var activeContext: kotlin.coroutines.CoroutineContext? = null
    private val extractor = com.example.readalong.data.audio.CanonicalMp3Extractor(context, uri, Mp3Extractor(Mp3Extractor.FLAG_DISABLE_ID3_METADATA)) { activeContext?.ensureActive() }
    private lateinit var input: DefaultExtractorInput
    private var seekMap: SeekMap? = null
    private var packet: Packet? = null
    private val pending = ByteArray(4096)
    private var pendingSize = 0
    private var ended = false
    private var sourceFormat: Format? = null
    private val output = object : TrackOutput {
        override fun format(format: Format) { sourceFormat = format }
        override fun sampleData(reader: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            check(length <= pending.size - pendingSize) { "MP3 frame exceeds the decoder buffer." }
            val count = reader.read(pending, pendingSize, length)
            if (count < 0) { if (!allowEndOfInput) throw EOFException(); return C.RESULT_END_OF_INPUT }
            pendingSize += count
            return count
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            check(length <= pending.size - pendingSize)
            data.readBytes(pending, pendingSize, length); pendingSize += length
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            check(offset == 0 && size == pendingSize && cryptoData == null)
            packet = Packet(pending.copyOf(size), timeUs)
            pendingSize = 0
        }
    }

    suspend fun open(startUs: Long) {
        activeContext = coroutineContext
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output
            override fun endTracks() = Unit
            override fun seekMap(value: SeekMap) { seekMap = value }
        })
        reopen(0)
        while (sourceFormat == null || seekMap == null) {
            coroutineContext.ensureActive()
            check(extractor.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) { "No MP3 frames were found." }
        }
        val target = (startUs - 1_000_000).coerceAtLeast(0)
        val point = requireNotNull(seekMap).getSeekPoints(target).first
        packet = null; pendingSize = 0
        reopen(point.position)
        extractor.seek(point.position, point.timeUs)
    }

    private fun reopen(position: Long) {
        data.close()
        val remaining = data.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
        input = DefaultExtractorInput(data, position, if (remaining == C.LENGTH_UNSET.toLong()) remaining else position + remaining)
    }

    suspend fun next(): Packet? {
        while (packet == null && !ended) {
            coroutineContext.ensureActive()
            ended = extractor.read(input, PositionHolder()) == Extractor.RESULT_END_OF_INPUT
        }
        return packet.also { packet = null }
    }

    override fun close() { try { data.close() } finally { extractor.release() } }
}
