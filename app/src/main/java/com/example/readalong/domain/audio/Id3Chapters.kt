package com.example.readalong.domain.audio

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** ID3v2 CHAP/TIT2, as described by https://id3.org/id3v2-chapters-1.0. */
object Id3Chapters {
    private const val MAX_TAG = 8 * 1024 * 1024
    fun read(channel: SeekableByteChannel, durationMs: Long? = null): List<AudioChapter> = try {
        fun bytes(size: Int): ByteBuffer {
            require(size in 0..MAX_TAG)
            val buffer = ByteBuffer.allocate(size)
            while (buffer.hasRemaining()) require(channel.read(buffer) > 0)
            buffer.flip(); return buffer
        }
        channel.position(0)
        val header = bytes(10)
        require(header.get() == 'I'.code.toByte() && header.get() == 'D'.code.toByte() && header.get() == '3'.code.toByte())
        val version = header.get().toInt() and 255
        require(version in 3..4)
        header.get()
        val flags = header.get().toInt() and 255
        require(flags and 0x80 == 0) // Unsupported tag-wide unsynchronisation falls back to file chapters.
        val size = syncSafe(header)
        val tag = bytes(size)
        if (flags and 0x40 != 0) {
            val extended = if (version == 4) syncSafe(tag) else tag.int + 4
            require(extended in 4..size); tag.position(extended)
        }
        val chapters = mutableListOf<AudioChapter>()
        frames(tag, version) { type, data ->
            if (type == "CHAP") {
                var terminated = false
                while (data.hasRemaining()) if (data.get() == 0.toByte()) { terminated = true; break }
                require(terminated && data.remaining() >= 16)
                val start = data.int.toLong() and 0xffffffffL
                data.int; data.int; data.int // End and byte offsets are optional for navigation.
                var title: String? = null
                frames(data, version) { subType, text ->
                    if (subType == "TIT2" && text.hasRemaining()) {
                        val encoding = text.get().toInt() and 255
                        val charset = when (encoding) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> error("Unknown text encoding") }
                        val content = ByteArray(text.remaining()).also(text::get)
                        title = String(content, charset).trimEnd('\u0000').trim().takeIf { it.isNotBlank() }
                    }
                }
                require(chapters.size < 10_000)
                chapters += AudioChapter(title ?: "Chapter ${chapters.size + 1}", start)
            }
        }
        val result = chapters.sortedBy { it.startMs }
        require(result.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
        require(result.all { durationMs == null || it.startMs < durationMs })
        result
    } catch (_: Exception) { emptyList() }

    private fun syncSafe(data: ByteBuffer): Int {
        var value = 0
        repeat(4) { val byte = data.get().toInt() and 255; require(byte < 128); value = (value shl 7) or byte }
        return value
    }
    private fun frames(data: ByteBuffer, version: Int, consume: (String, ByteBuffer) -> Unit) {
        var count = 0
        while (data.remaining() >= 10 && data.get(data.position()) != 0.toByte()) {
            require(++count <= 20_000)
            val name = ByteArray(4).also(data::get)
            val size = if (version == 4) syncSafe(data) else data.int
            val flags = data.short.toInt() and 65535
            require(size >= 0 && size <= data.remaining())
            val payload = data.slice().apply { limit(size) }
            // Compression, encryption and grouping need a decoder; skip unsupported frames safely.
            if (flags and 255 == 0) consume(String(name, Charsets.ISO_8859_1), payload)
            data.position(data.position() + size)
        }
    }
}
