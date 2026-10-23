package com.example.readalong.domain.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel

/** Reads chapter metadata by seeking around media data; never buffers an audiobook. */
data class AudioChapter(val title: String, val startMs: Long)

object Mp4Chapters {
    private const val MAX_TABLE = 100_000
    private const val MAX_CHAPTERS = 10_000
    private const val MAX_METADATA = 8 * 1024 * 1024

    /** Movie duration is independent of Android codec/track discovery, including long M4Bs. */
    fun duration(channel: SeekableByteChannel): Long? = try {
        val reader = Reader(channel)
        val movie = reader.boxes(0, channel.size()).firstOrNull { it.type == "moov" }
        val header = movie?.let { reader.children(it).firstOrNull { box -> box.type == "mvhd" } }
        header?.let {
            val data = reader.payload(it)
            val version = data.get(0).toInt() and 255
            require(version in 0..1)
            val scale = data.getInt(if (version == 1) 20 else 12).toLong() and 0xffffffffL
            val ticks = if (version == 1) data.getLong(24) else data.getInt(16).toLong() and 0xffffffffL
            require(scale > 0 && ticks > 0 && ticks <= Long.MAX_VALUE / 1000)
            (ticks * 1000 / scale).takeIf { value -> value > 0 }
        }
    } catch (_: Exception) { null }

    fun read(channel: SeekableByteChannel, durationMs: Long? = null): List<AudioChapter> = try {
        val reader = Reader(channel)
        val moov = reader.boxes(0, channel.size()).firstOrNull { it.type == "moov" }
        val result = if (moov == null) emptyList() else {
            val children = reader.children(moov)
            runCatching { reader.quickTime(children) }.getOrDefault(emptyList()).ifEmpty {
                children.firstOrNull { it.type == "udta" }?.let { udta ->
                    reader.children(udta).firstOrNull { it.type == "chpl" }?.let(reader::nero)
                }.orEmpty()
            }
        }
        require(result.size <= MAX_CHAPTERS)
        require(result.zipWithNext().all { (a, b) -> b.startMs > a.startMs })
        require(result.all { it.startMs >= 0 && (durationMs == null || it.startMs < durationMs) })
        result
    } catch (_: Exception) { emptyList() } // Optional metadata cannot make a playable file fail import.

    private data class Box(val type: String, val data: Long, val end: Long)
    private class Reader(val channel: SeekableByteChannel) {
        private var visited = 0
        fun bytes(position: Long, size: Int): ByteBuffer {
            require(position >= 0 && size in 0..MAX_METADATA && position <= channel.size() - size)
            val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            channel.position(position)
            while (buffer.hasRemaining()) require(channel.read(buffer) > 0)
            buffer.flip()
            return buffer
        }
        fun boxes(start: Long, end: Long): List<Box> {
            val boxes = mutableListOf<Box>()
            var cursor = start
            while (cursor < end) {
                require(++visited <= MAX_TABLE && end - cursor >= 8)
                val header = bytes(cursor, 8)
                var size = header.int.toLong() and 0xffffffffL
                val typeBytes = ByteArray(4).also(header::get)
                val type = String(typeBytes, Charsets.ISO_8859_1)
                val headerSize = if (size == 1L) { size = bytes(cursor + 8, 8).long; 16 } else 8
                if (size == 0L) size = end - cursor
                require(size >= headerSize && size <= end - cursor)
                boxes += Box(type, cursor + headerSize, cursor + size)
                cursor += size
            }
            return boxes
        }
        fun children(box: Box) = boxes(box.data, box.end)
        fun payload(box: Box) = bytes(box.data, Math.toIntExact(box.end - box.data))
        fun nero(box: Box): List<AudioChapter> {
            val data = payload(box)
            val version = data.get().toInt() and 255
            require(version in 0..1)
            data.position(if (version == 1) 8 else 4)
            val count = data.get().toInt() and 255
            return List(count) { index ->
                val time = data.long
                require(time >= 0)
                val title = ByteArray(data.get().toInt() and 255).also(data::get)
                AudioChapter(String(title, Charsets.UTF_8).trim().ifBlank { "Chapter ${index + 1}" }, time / 10_000)
            }
        }
        fun quickTime(moov: List<Box>): List<AudioChapter> {
            data class Track(val id: Int, val parts: List<Box>)
            val tracks = moov.filter { it.type == "trak" }.mapNotNull { trak ->
                val parts = children(trak)
                val tkhd = parts.firstOrNull { it.type == "tkhd" } ?: return@mapNotNull null
                val header = payload(tkhd)
                val version = header.get(0).toInt() and 255
                require(version in 0..1)
                Track(header.getInt(if (version == 1) 20 else 12), parts)
            }
            val chapterIds = tracks.flatMap { track ->
                track.parts.firstOrNull { it.type == "tref" }?.let { children(it) }.orEmpty()
                    .filter { it.type == "chap" }.flatMap { chap ->
                        val buffer = payload(chap)
                        require(buffer.remaining() % 4 == 0)
                        List(buffer.remaining() / 4) { buffer.int }
                    }
            }
            val chapterTrack = tracks.firstOrNull { it.id in chapterIds } ?: return emptyList()
            val mdia = chapterTrack.parts.firstOrNull { it.type == "mdia" } ?: return emptyList()
            val parts = children(mdia)
            val handler = parts.firstOrNull { it.type == "hdlr" } ?: return emptyList()
            val typeBytes = ByteArray(4)
            payload(handler).apply { position(8); get(typeBytes) }
            require(String(typeBytes, Charsets.ISO_8859_1) == "text")
            val mdhd = payload(parts.first { it.type == "mdhd" })
            val version = mdhd.get(0).toInt() and 255
            require(version in 0..1)
            val scale = mdhd.getInt(if (version == 1) 20 else 12).toLong() and 0xffffffffL
            require(scale > 0)
            val minf = children(parts.first { it.type == "minf" })
            val tables = children(minf.first { it.type == "stbl" })
            fun table(type: String) = payload(tables.first { it.type == type }).apply { position(4) }
            fun count(buffer: ByteBuffer, max: Int = MAX_TABLE): Int = buffer.int.also { require(it in 1..max) }
            val stsz = table("stsz")
            val fixedSize = stsz.int
            require(fixedSize >= 0)
            val sizeCount = count(stsz, MAX_CHAPTERS)
            val sizes = IntArray(sizeCount) { if (fixedSize == 0) stsz.int else fixedSize }
            require(sizes.all { it in 2..65536 })
            val offsets = tables.firstOrNull { it.type == "co64" }?.let { box ->
                val data = payload(box).apply { position(4) }
                LongArray(count(data)) { data.long }
            } ?: table("stco").let { data -> LongArray(count(data)) { data.int.toLong() and 0xffffffffL } }
            data class ChunkRun(val first: Int, val samples: Int)
            val stsc = table("stsc")
            val chunks = List(count(stsc)) {
                val first = stsc.int
                val samples = stsc.int
                stsc.int // Sample description ID.
                require(first > 0 && samples in 1..MAX_CHAPTERS)
                ChunkRun(first, samples)
            }
            require(chunks.first().first == 1 && chunks.zipWithNext().all { (a, b) -> b.first > a.first })
            val stts = table("stts")
            var tick = 0L
            val timestamps = ArrayList<Long>(sizeCount)
            repeat(count(stts)) {
                val samples = stts.int
                val delta = stts.int.toLong() and 0xffffffffL
                require(samples > 0 && samples <= sizeCount - timestamps.size )
                repeat(samples) {
                    timestamps += Math.addExact(Math.multiplyExact(tick / scale, 1000), (tick % scale) * 1000 / scale)
                    tick = Math.addExact(tick, delta)
                }
            }
            require(timestamps.size == sizeCount)
            val chapters = ArrayList<AudioChapter>(sizeCount)
            var sample = 0
            var run = 0
            offsets.forEachIndexed { index, chunkOffset ->
                while (run < chunks.lastIndex && chunks[run + 1].first <= index + 1) run++
                var offset = chunkOffset
                repeat(chunks[run].samples) {
                    require(sample < sizeCount)
                    val data = bytes(offset, sizes[sample])
                    val length = data.short.toInt() and 65535
                    require(length <= data.remaining())
                    val text = ByteArray(length).also(data::get)
                    val charset = if (text.size >= 2 && ((text[0] == 0xfe.toByte() && text[1] == 0xff.toByte()) ||
                        (text[0] == 0xff.toByte() && text[1] == 0xfe.toByte()))) Charsets.UTF_16 else Charsets.UTF_8
                    val title = String(text, charset).trim().ifBlank { "Chapter ${sample + 1}" }
                    chapters += AudioChapter(title, timestamps[sample])
                    offset = Math.addExact(offset, sizes[sample].toLong())
                    sample++
                }
            }
            require(sample == sizeCount)
            return chapters
        }
    }
}
