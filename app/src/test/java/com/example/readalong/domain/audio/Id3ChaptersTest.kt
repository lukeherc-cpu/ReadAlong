package com.example.readalong.domain.audio

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Files

class Id3ChaptersTest {
    private fun size(value: Int, version: Int) = if (version == 3) ByteBuffer.allocate(4).putInt(value).array()
        else byteArrayOf((value shr 21).toByte(), ((value shr 14) and 127).toByte(), ((value shr 7) and 127).toByte(), (value and 127).toByte())
    private fun frame(name: String, bytes: ByteArray, version: Int) = name.toByteArray() + size(bytes.size, version) + byteArrayOf(0, 0) + bytes
    private fun tag(version: Int): ByteArray {
        val chapters = listOf(0 to "Opening", 1250 to "♪ ♫").mapIndexed { index, (time, title) ->
            frame("CHAP", "c$index\u0000".toByteArray() + ByteBuffer.allocate(16).putInt(time).putInt(-1).putInt(-1).putInt(-1).array() +
                frame("TIT2", byteArrayOf(3) + title.toByteArray(), version), version)
        }.reduce { a, b -> a + b }
        return "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + size(chapters.size, 4) + chapters
    }
    private fun read(data: ByteArray): List<AudioChapter> {
        val file = Files.createTempFile("id3-chapters", ".mp3")
        try { Files.write(file, data); return Files.newByteChannel(file).use { Id3Chapters.read(it, 3000) } }
        finally { Files.delete(file) }
    }
    @Test fun versionThreeAndFourChapterTimesAndTitles() {
        for (version in 3..4) assertEquals(listOf(AudioChapter("Opening", 0), AudioChapter("♪ ♫", 1250)), read(tag(version)))
    }
    @Test fun malformedOrUnsupportedTagsReturnNoChapters() {
        assertTrue(read(tag(4).dropLast(1).toByteArray()).isEmpty())
        assertTrue(read(tag(4).apply { this[5] = 0x80.toByte() }).isEmpty())
        assertTrue(read(ByteArray(10)).isEmpty())
    }
}
