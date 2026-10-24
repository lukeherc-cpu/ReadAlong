package com.example.readalong.domain.audio

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Reads AAC packet locations straight from MP4 tables, without expanding millions of samples.
 * Only small run tables and two 16 KiB table pages are retained. All times/offsets are 64 bit.
 * Unsupported layouts use the existing general-purpose extractor instead. */
class AacMp4Index(private val channel: FileChannel, private val base: Long = 0, length: Long = channel.size() - base) : Closeable {
    class Unsupported(message: String) : IOException(message)
    data class Packet(val bytes: ByteArray, val timeUs: Long)
    private data class Box(val type: String, val data: Long, val end: Long)
    private data class TimeRun(val firstSample: Long, val firstTick: Long, val count: Long, val delta: Long)
    private data class ChunkRun(val firstChunk: Long, val firstSample: Long, val samplesPerChunk: Long)
    private val end = length
    private var sizePageAt = -1L
    private var sizePage = ByteArray(0)
    private var chunkPageAt = -1L
    private var chunkPage = ByteArray(0)
    private val sizes: Box
    private val chunks: Box
    private val wideChunks: Boolean
    private val fixedSize: Long
    val sampleCount: Long
    private val chunkCount: Long
    private val timescale: Long
    private val timeRuns: List<TimeRun>
    private val chunkRuns: List<ChunkRun>
    private val mediaStartTicks: Long
    private val emptyEditUs: Long
    val audioSpecificConfig: ByteArray
    private var sample = 0L
    private var chunk = 0L
    private var chunkEndSample = 0L
    private var packetOffset = 0L
    private var timeRunIndex = 0

    init {
        require(base >= 0 && length > 0)
        val top = boxes(0, end)
        if (top.any { it.type == "moof" }) throw Unsupported("Fragmented MP4 uses the general extractor.")
        val movie = top.firstOrNull { it.type == "moov" } ?: throw Unsupported("No MP4 movie header.")
        val movieChildren = boxes(movie.data, movie.end)
        val mvhd = movieChildren.firstOrNull { it.type == "mvhd" } ?: throw Unsupported("No MP4 movie clock.")
        val movieScale = clock(mvhd)
        val audioTracks = movieChildren.filter { it.type == "trak" }.mapNotNull { track ->
            val children = boxes(track.data, track.end)
            val mdia = children.firstOrNull { it.type == "mdia" } ?: return@mapNotNull null
            val mdiaChildren = boxes(mdia.data, mdia.end)
            val handler = mdiaChildren.firstOrNull { it.type == "hdlr" } ?: return@mapNotNull null
            if (String(bytes(handler.data + 8, 4), Charsets.US_ASCII) != "soun") null else children to mdiaChildren
        }
        val (track, media) = audioTracks.firstOrNull() ?: throw Unsupported("No audio track.")
        timescale = clock(media.first { it.type == "mdhd" })
        val minf = media.first { it.type == "minf" }
        val stbl = boxes(minf.data, minf.end).first { it.type == "stbl" }
        val tables = boxes(stbl.data, stbl.end)
        if (tables.any { it.type in setOf("senc", "saiz", "saio") }) throw Unsupported("Encrypted AAC uses the general extractor.")
        val descriptions = tables.first { it.type == "stsd" }
        if (u32(descriptions.data + 4) != 1L) throw Unsupported("Changing audio configurations use the general extractor.")
        val entry = boxes(descriptions.data + 8, descriptions.end).single()
        if (entry.type != "mp4a" || u16(entry.data + 8) != 0) throw Unsupported("This is not a supported AAC sample entry.")
        val esds = boxes(entry.data + 28, entry.end).firstOrNull { it.type == "esds" }
            ?: throw Unsupported("No AAC configuration.")
        if (esds.end - esds.data > 64 * 1024) throw Unsupported("AAC configuration is too large.")
        audioSpecificConfig = decoderConfig(bytes(esds.data + 4, (esds.end - esds.data - 4).toInt()))
        sizes = tables.firstOrNull { it.type == "stsz" } ?: throw Unsupported("Compact sample sizes use the general extractor.")
        fixedSize = u32(sizes.data + 4)
        sampleCount = u32(sizes.data + 8)
        checkData(sampleCount in 1..50_000_000 && (fixedSize != 0L || sampleCount <= (sizes.end - sizes.data - 12) / 4), "Invalid AAC sample sizes.")
        chunks = tables.firstOrNull { it.type == "co64" || it.type == "stco" } ?: throw Unsupported("No MP4 chunk locations.")
        wideChunks = chunks.type == "co64"
        chunkCount = u32(chunks.data + 4)
        checkData(chunkCount in 1..sampleCount && chunkCount <= (chunks.end - chunks.data - 8) / if (wideChunks) 8 else 4, "Invalid AAC chunks.")
        val stts = tables.first { it.type == "stts" }
        val timeCount = entryCount(stts, 8)
        var firstSample = 0L
        var firstTick = 0L
        timeRuns = (0 until timeCount).map { n ->
            val count = u32(stts.data + 8 + n * 8L)
            val delta = u32(stts.data + 12 + n * 8L)
            checkData(count > 0 && delta > 0 && firstSample + count <= sampleCount, "Invalid AAC clock run.")
            TimeRun(firstSample, firstTick, count, delta).also {
                firstSample += count
                firstTick = Math.addExact(firstTick, Math.multiplyExact(count, delta))
            }
        }
        checkData(firstSample == sampleCount, "Incomplete AAC clock.")
        if (tables.any { it.type == "ctts" }) throw Unsupported("Composition offsets use the general extractor.")
        val stsc = tables.first { it.type == "stsc" }
        val chunkRunCount = entryCount(stsc, 12)
        var samplesBefore = 0L
        chunkRuns = (0 until chunkRunCount).map { n ->
            val at = stsc.data + 8 + n * 12L
            val first = u32(at) - 1
            val perChunk = u32(at + 4)
            val next = if (n + 1 < chunkRunCount) u32(at + 12) - 1 else chunkCount
            checkData(first >= 0 && next > first && next <= chunkCount && perChunk > 0 && u32(at + 8) == 1L, "Invalid AAC chunk run.")
            if (n == 0) checkData(first == 0L, "AAC chunks do not begin at zero.")
            ChunkRun(first, samplesBefore, perChunk).also {
                samplesBefore = Math.addExact(samplesBefore, Math.multiplyExact(next - first, perChunk))
            }
        }
        checkData(samplesBefore == sampleCount, "Incomplete AAC chunk table.")
        var editStart = 0L
        var empty = 0L
        track.firstOrNull { it.type == "edts" }?.let { edits ->
            boxes(edits.data, edits.end).firstOrNull { it.type == "elst" }?.let { edit ->
                val version = bytes(edit.data, 1)[0].toInt()
                if (version !in 0..1) throw Unsupported("Unknown MP4 edit version.")
                val count = entryCount(edit, if (version == 1) 20 else 12)
                if (count !in 1..2) throw Unsupported("Complex MP4 edits use the general extractor.")
                for (n in 0 until count) {
                    val at = edit.data + 8 + n * if (version == 1) 20L else 12L
                    val duration = if (version == 1) i64(at) else u32(at)
                    val mediaTime = if (version == 1) i64(at + 8) else i32(at + 4).toLong()
                    val rateAt = at + if (version == 1) 16 else 8
                    if (u32(rateAt) != 0x00010000L) throw Unsupported("Non-unit MP4 edit rate.")
                    if (mediaTime == -1L && n == 0 && count == 2) empty = micros(duration, movieScale)
                    else if (mediaTime >= 0 && n == count - 1) editStart = mediaTime
                    else throw Unsupported("Complex MP4 edits use the general extractor.")
                }
            }
        }
        mediaStartTicks = editStart
        emptyEditUs = empty
    }

    fun seek(timeUs: Long) {
        val ticks = Math.addExact(mediaStartTicks, Math.multiplyExact(timeUs - emptyEditUs, timescale) / 1_000_000).coerceAtLeast(0)
        timeRunIndex = floorIndex(timeRuns.size) { timeRuns[it].firstTick <= ticks }
        val run = timeRuns[timeRunIndex]
        sample = (run.firstSample + (ticks - run.firstTick) / run.delta).coerceAtMost(sampleCount)
        if (sample == sampleCount) return
        val chunksRun = chunkRuns[floorIndex(chunkRuns.size) { chunkRuns[it].firstSample <= sample }]
        chunk = chunksRun.firstChunk + (sample - chunksRun.firstSample) / chunksRun.samplesPerChunk
        val first = chunksRun.firstSample + (chunk - chunksRun.firstChunk) * chunksRun.samplesPerChunk
        chunkEndSample = first + chunksRun.samplesPerChunk
        // Ordinary AAC chunks contain a handful of packets. Extremely large chunks are
        // delegated rather than linearly scanning hours of sizes on every seek.
        if (sample - first > 4096) throw Unsupported("Large single-chunk AAC uses the general extractor.")
        packetOffset = chunkOffset(chunk)
        for (s in first until sample) packetOffset += sampleSize(s)
    }

    fun next(): Packet? {
        if (sample >= sampleCount) return null
        while (timeRunIndex + 1 < timeRuns.size && sample >= timeRuns[timeRunIndex + 1].firstSample) timeRunIndex++
        val run = timeRuns[timeRunIndex]
        val size = sampleSize(sample)
        checkData(size in 1..2_097_152, "Invalid AAC packet size.")
        val result = Packet(bytes(packetOffset, size.toInt()), micros(run.firstTick + (sample - run.firstSample) * run.delta - mediaStartTicks, timescale) + emptyEditUs)
        packetOffset += size
        sample++
        if (sample == chunkEndSample && sample < sampleCount) {
            chunk++
            val chunksRun = chunkRuns[floorIndex(chunkRuns.size) { chunkRuns[it].firstChunk <= chunk }]
            chunkEndSample += chunksRun.samplesPerChunk
            packetOffset = chunkOffset(chunk)
        }
        return result
    }

    private fun sampleSize(n: Long): Long {
        if (fixedSize != 0L) return fixedSize
        val at = sizes.data + 12 + n * 4
        if (at !in sizePageAt until sizePageAt + sizePage.size - 3) {
            sizePageAt = at
            sizePage = bytes(at, minOf(16_384L, sizes.end - at).toInt())
        }
        return ByteBuffer.wrap(sizePage, (at - sizePageAt).toInt(), 4).int.toLong() and 0xffffffffL
    }
    private fun chunkOffset(n: Long): Long {
        val width = if (wideChunks) 8 else 4
        val at = chunks.data + 8 + n * width
        if (at !in chunkPageAt until chunkPageAt + chunkPage.size - width + 1) {
            chunkPageAt = at
            chunkPage = bytes(at, minOf(16_384L, chunks.end - at).toInt())
        }
        val b = ByteBuffer.wrap(chunkPage, (at - chunkPageAt).toInt(), width)
        return if (wideChunks) b.long.also { checkData(it >= 0, "Invalid 64-bit AAC offset.") } else b.int.toLong() and 0xffffffffL
    }
    private fun boxes(from: Long, until: Long): List<Box> {
        val result = mutableListOf<Box>()
        var at = from
        while (at + 8 <= until) {
            var size = u32(at)
            val type = String(bytes(at + 4, 4), Charsets.US_ASCII)
            var header = 8
            if (size == 1L) { size = i64(at + 8); header = 16 }
            if (size == 0L) size = until - at
            checkData(size >= header && size <= until - at && result.size < 10_000, "Invalid MP4 box.")
            result += Box(type, at + header, at + size)
            at += size
        }
        return result
    }
    private fun clock(box: Box): Long {
        val version = bytes(box.data, 1)[0].toInt()
        if (version !in 0..1) throw Unsupported("Unknown MP4 clock version.")
        return u32(box.data + if (version == 1) 20 else 12).also { checkData(it > 0, "Invalid MP4 clock.") }
    }
    private fun entryCount(box: Box, width: Int): Int = u32(box.data + 4).also {
        checkData(it in 1..100_000 && it <= (box.end - box.data - 8) / width, "Invalid MP4 run table.")
    }.toInt()
    private fun bytes(at: Long, count: Int): ByteArray {
        checkData(at >= 0 && count >= 0 && at <= end - count, "MP4 read outside the audio file.")
        val data = ByteArray(count)
        val buffer = ByteBuffer.wrap(data)
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, base + at + buffer.position())
            if (n <= 0) throw IOException("Unexpected end of MP4 audio.")
        }
        return data
    }
    private fun u16(at: Long) = ByteBuffer.wrap(bytes(at, 2)).short.toInt() and 0xffff
    private fun i32(at: Long) = ByteBuffer.wrap(bytes(at, 4)).int
    private fun u32(at: Long) = i32(at).toLong() and 0xffffffffL
    private fun i64(at: Long) = ByteBuffer.wrap(bytes(at, 8)).long
    override fun close() = Unit // Caller owns its source descriptor/channel.

    companion object {
        private fun checkData(condition: Boolean, message: String) { if (!condition) throw IOException(message) }
        private fun micros(ticks: Long, scale: Long) = Math.multiplyExact(ticks, 1_000_000L) / scale
        private inline fun floorIndex(count: Int, before: (Int) -> Boolean): Int {
            var lo = 0; var hi = count
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (before(mid)) lo = mid + 1 else hi = mid }
            return (lo - 1).coerceAtLeast(0)
        }
        private fun decoderConfig(data: ByteArray): ByteArray {
            fun scan(from: Int, end: Int, depth: Int): ByteArray? {
                if (depth > 4) throw Unsupported("Invalid AAC descriptor nesting.")
                var at = from
                while (at < end) {
                    val tag = data[at++].toInt() and 255
                    var length = 0
                    var count = 0
                    do {
                        if (at >= end || count++ == 4) throw Unsupported("Invalid AAC descriptor length.")
                        val next = data[at++].toInt() and 255
                        length = (length shl 7) or (next and 127)
                    } while (next and 128 != 0)
                    if (length > end - at) throw Unsupported("Truncated AAC descriptor.")
                    val until = at + length
                    if (tag == 5) {
                        if (length !in 2..64) throw Unsupported("Invalid AAC decoder configuration.")
                        return data.copyOfRange(at, until)
                    }
                    var child = until
                    if (tag == 3 && length >= 3) {
                        val flags = data[at + 2].toInt() and 255
                        child = at + 3
                        if (flags and 128 != 0) child += 2
                        if (flags and 64 != 0) { if (child >= until) throw Unsupported("Invalid AAC URL."); child += 1 + (data[child].toInt() and 255) }
                        if (flags and 32 != 0) child += 2
                    } else if (tag == 4 && length >= 13) {
                        if (data[at].toInt() and 255 !in setOf(0x40, 0x66, 0x67, 0x68)) throw Unsupported("Unsupported AAC object type.")
                        child = at + 13
                    }
                    if (child < until) scan(child, until, depth + 1)?.let { return it }
                    at = until
                }
                return null
            }
            return scan(0, data.size, 0) ?: throw Unsupported("AAC decoder configuration is missing.")
        }
    }
}
