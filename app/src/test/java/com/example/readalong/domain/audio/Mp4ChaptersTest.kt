package com.example.readalong.domain.audio

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files

class Mp4ChaptersTest {
    private fun box(type: String, data: ByteArray): ByteArray = ByteBuffer.allocate(data.size + 8)
        .putInt(data.size + 8).put(type.toByteArray()).put(data).array()
    private fun nero(): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(0x01000000); out.writeInt(0); out.writeByte(2)
            listOf(0L to "Opening", 1250L to "♪ ♫").forEach { (ms, title) ->
                val bytes = title.toByteArray(); out.writeLong(ms * 10_000); out.writeByte(bytes.size); out.write(bytes)
            }
        }
        return box("moov", box("udta", box("chpl", buffer.toByteArray())))
    }
    private fun read(bytes: ByteArray, duration: Long? = null): List<AudioChapter> {
        val path = Files.createTempFile("chapters", ".m4b")
        try { Files.write(path, bytes); return Files.newByteChannel(path).use { Mp4Chapters.read(it, duration) } }
        finally { Files.delete(path) }
    }
    @Test fun movieDurationUsesUnsignedAnd64BitTimesWithoutAudioTrackDiscovery() {
        for ((version, ticks) in listOf(0 to 3_000_000_000L, 1 to 8_294_400_000L)) {
            val payload = ByteBuffer.allocate(if (version == 0) 20 else 32).apply {
                putInt(version shl 24)
                if (version == 0) { putInt(0); putInt(0) } else { putLong(0); putLong(0) }
                putInt(48_000)
                if (version == 0) putInt(ticks.toInt()) else putLong(ticks)
            }.array()
            val path = Files.createTempFile("duration", ".m4b")
            try {
                Files.write(path, box("moov", box("mvhd", payload)))
                assertEquals(ticks * 1000 / 48_000, Files.newByteChannel(path).use { Mp4Chapters.duration(it) })
            } finally { Files.delete(path) }
        }
    }
    @Test fun neroTimesAndUnicodeRoundTrip() {
        assertEquals(listOf(AudioChapter("Opening", 0), AudioChapter("♪ ♫", 1250)), read(nero(), 2000))
        assertTrue(read(nero(), 1000).isEmpty())
    }
    @Test fun quickTimeChapterTrackSupportsZeroDurationFinalSample() {
        fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).apply { values.forEach { putInt(it) } }.array()
        val samples = listOf("First", "Second").map { title ->
            val text = title.toByteArray(); ByteBuffer.allocate(text.size + 2).putShort(text.size.toShort()).put(text).array()
        }
        val media = box("mdat", samples.reduce { a, b -> a + b })
        val audioTrack = box("trak", box("tkhd", ints(0, 0, 0, 1)) + box("tref", box("chap", ints(2))))
        val tables = box("stts", ints(0, 2, 1, 1250, 1, 0)) + box("stsz", ints(0, 0, 2, samples[0].size, samples[1].size)) +
            box("stsc", ints(0, 1, 1, 2, 1)) + box("stco", ints(0, 1, 8))
        val chapterTrack = box("trak", box("tkhd", ints(0, 0, 0, 2)) + box("mdia",
            box("mdhd", ints(0, 0, 0, 1000)) + box("hdlr", ints(0, 0) + "text".toByteArray()) + box("minf", box("stbl", tables))))
        assertEquals(listOf(AudioChapter("First", 0), AudioChapter("Second", 1250)), read(media + box("moov", audioTrack + chapterTrack), 2000))
    }
    @Test fun corruptOrTruncatedMetadataFallsBackWithoutFailure() {
        assertTrue(read(nero().dropLast(2).toByteArray()).isEmpty())
        assertTrue(read(ByteBuffer.allocate(8).putInt(-1).put("moov".toByteArray()).array()).isEmpty())
        assertTrue(read(ByteArray(7)).isEmpty())
    }
    @Test fun seeksPastLargeMediaAtomInsteadOfBufferingIt() {
        val path = Files.createTempFile("large-chapters", ".m4b")
        try {
            RandomAccessFile(path.toFile(), "rw").use { file ->
                val mediaSize = 3_000_000_000L
                file.writeInt(1); file.writeBytes("mdat"); file.writeLong(mediaSize)
                file.seek(mediaSize); file.write(nero())
            }
            assertEquals(2, Files.newByteChannel(path).use { Mp4Chapters.read(it) }.size)
        } finally { Files.delete(path) }
    }
    @Test fun optionalPrivateCollectionMatchesIndependentlyProbedChapterCounts() {
        val inventory = System.getProperty("privateChapterInventory")?.let(::File)
        assumeTrue("Optional local collection isn't present", inventory?.isFile == true)
        // Tab-separated path, count, duration: ffprobe produces this ignored local manifest.
        inventory!!.readLines().forEach { line ->
            val fields = line.split('\t')
            val file = File(fields[0])
            val chapters = Files.newByteChannel(file.toPath()).use { if (file.name.endsWith(".mp3")) Id3Chapters.read(it, fields[2].toLong()) else Mp4Chapters.read(it, fields[2].toLong()) }
            assertEquals(file.name, fields[1].toInt(), chapters.size)
            assertTrue(chapters.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
        }
    }
}
