package com.example.readalong.data.audio

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.*
import androidx.media3.extractor.mp3.Mp3Extractor
import java.io.*
import java.util.LinkedHashMap

/** Exact compressed-frame positions, sampled about once a second. No PCM or book text is cached. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class Mp3SeekIndex(private val times: LongArray, private val positions: LongArray,
    private val duration: Long) : SeekMap {
    override fun isSeekable() = true
    override fun getDurationUs() = duration
    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val found = times.binarySearch(timeUs)
        val i = (if (found >= 0) found else -found - 2).coerceIn(0, times.lastIndex)
        val first = SeekPoint(times[i], positions[i])
        return if (i < times.lastIndex && first.timeUs < timeUs) SeekMap.SeekPoints(first, SeekPoint(times[i + 1], positions[i + 1]))
            else SeekMap.SeekPoints(first)
    }
    fun timeAt(position: Long): Long {
        val i = positions.binarySearch(position)
        check(i >= 0) { "MP3 seek must use an indexed frame boundary." }
        return times[i]
    }
    fun estimatedTimeAt(position: Long): Long {
        val found = positions.binarySearch(position)
        if (found >= 0) return times[found]
        val i = (-found - 2).coerceIn(0, positions.lastIndex)
        if (i == positions.lastIndex || position < positions.first()) return times[i]
        val fraction = (position - positions[i]).toDouble() / (positions[i + 1] - positions[i])
        return times[i] + ((times[i + 1] - times[i]) * fraction).toLong()
    }

    companion object {
        private const val MAGIC = 0x52414d50
        private val lock = Any()
        private val cached = object : LinkedHashMap<String, Mp3SeekIndex>(8, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mp3SeekIndex>?) = size > 4
        }
        private fun identity(context: Context, uri: Uri): String {
            val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
            val modified = runCatching { context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
                val column = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (column >= 0 && it.moveToFirst()) it.getLong(column) else 0L
            } }.getOrNull() ?: 0L
            return java.security.MessageDigest.getInstance("SHA-256").digest("v1:$uri:$size:$modified".toByteArray()).joinToString("") { "%02x".format(it) }
        }
        /** Drops this file's saved frame positions so the next read scans it again. */
        fun forget(context: Context, uri: Uri) = synchronized(lock) {
            val key = identity(context, uri)
            cached.remove(key)
            File(context.filesDir, "mp3-seek-indexes").listFiles()?.filter { it.name.startsWith("$key.") }?.forEach { it.delete() }
        }
        /** Runs on Media3's loader or decoder's IO dispatcher. Reuses a verified, atomically
         * written index; an interrupted scan leaves no published cache file. */
        fun load(context: Context, uri: Uri, checkCancelled: () -> Unit = {}): Mp3SeekIndex = synchronized(lock) {
            checkCancelled()
            val key = identity(context, uri)
            cached[key]?.let { return@synchronized it }
            val directory = File(context.filesDir, "mp3-seek-indexes").apply { mkdirs() }
            val file = File(directory, "$key.frames")
            val saved = runCatching {
                val checksum = java.util.zip.CRC32()
                DataInputStream(java.util.zip.CheckedInputStream(BufferedInputStream(file.inputStream()), checksum)).use { input ->
                check(input.readInt() == MAGIC && input.readInt() == 1)
                val duration = input.readLong(); val count = input.readInt()
                check(count in 1..1_000_000 && file.length() == 28L + count * 16L)
                val times = LongArray(count); val positions = LongArray(count)
                for (i in 0 until count) {
                    times[i] = input.readLong(); positions[i] = input.readLong()
                    check(times[i] >= 0 && positions[i] >= 0 && (i == 0 || times[i] > times[i-1] && positions[i] > positions[i-1]))
                }
                check(duration >= times.last())
                val calculated = checksum.value
                check(input.readLong() == calculated) { "MP3 seek cache checksum does not match." }
                Mp3SeekIndex(times, positions, duration)
            } }.getOrNull()
            val result = saved ?: build(context, uri, checkCancelled).also { index ->
                val temporary = File(directory, "$key.${java.util.UUID.randomUUID()}.part")
                try {
                    FileOutputStream(temporary).use { stream ->
                        val checksum = java.util.zip.CRC32()
                        val output = DataOutputStream(java.util.zip.CheckedOutputStream(BufferedOutputStream(stream), checksum))
                        output.writeInt(MAGIC); output.writeInt(1); output.writeLong(index.duration); output.writeInt(index.times.size)
                        for (i in index.times.indices) { output.writeLong(index.times[i]); output.writeLong(index.positions[i]) }
                        output.flush()
                        output.writeLong(checksum.value)
                        output.flush(); stream.fd.sync()
                    }
                    check(temporary.renameTo(file)) { "Could not save accurate MP3 seek positions." }
                } finally { temporary.delete() }
            }
            cached[key] = result
            result
        }

        private fun build(context: Context, uri: Uri, checkCancelled: () -> Unit): Mp3SeekIndex {
            val data = DefaultDataSource.Factory(context).createDataSource()
            val extractor = Mp3Extractor(Mp3Extractor.FLAG_DISABLE_ID3_METADATA)
            val times = ArrayList<Long>(); val positions = ArrayList<Long>()
            var duration = C.TIME_UNSET
            var lastSampleUs = C.TIME_UNSET
            var packetSize = 0
            val buffer = ByteArray(4096)
            lateinit var input: DefaultExtractorInput
            val track = object : TrackOutput {
                override fun format(format: Format) = Unit
                override fun sampleData(reader: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                    val n = reader.read(buffer, 0, minOf(length, buffer.size))
                    if (n > 0) packetSize += n
                    return n
                }
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length); packetSize += length }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    check(offset == 0 && packetSize == size)
                    lastSampleUs = timeUs
                    if (times.isEmpty() || timeUs - times.last() >= 1_000_000) {
                        check(times.size < 1_000_000) { "This MP3 exceeds the seek index limit." }
                        times += timeUs; positions += input.position - size
                    }
                    packetSize = 0
                }
            }
            try {
                val size = data.open(DataSpec.Builder().setUri(uri).build())
                input = DefaultExtractorInput(data, 0, size)
                extractor.init(object : ExtractorOutput {
                    override fun track(id: Int, type: Int): TrackOutput = track
                    override fun endTracks() = Unit
                    override fun seekMap(map: SeekMap) { duration = map.durationUs }
                })
                val holder = PositionHolder()
                while (true) {
                    checkCancelled()
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("MP3 indexing interrupted")
                    val status = extractor.read(input, holder)
                    if (status == Extractor.RESULT_END_OF_INPUT) break
                    check(status != Extractor.RESULT_SEEK) { "MP3 indexing must read sequentially." }
                }
                check(times.isNotEmpty()) { "MP3 did not provide a valid frame timeline." }
                // The Xing/VBRI header's frame count can be short (or missing) when a file was cut or
                // joined after encoding. The frames actually scanned are the truth; never fail on the header.
                return Mp3SeekIndex(times.toLongArray(), positions.toLongArray(), if (duration == C.TIME_UNSET) lastSampleUs else maxOf(duration, lastSampleUs))
            } finally { try { data.close() } finally { extractor.release() } }
        }
    }
}
