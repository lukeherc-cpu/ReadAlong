package com.example.readalong.domain

import com.example.readalong.domain.audio.AacMp4Index
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

class AacMp4IndexTest {
    private fun data(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { out -> DataOutputStream(out).use(write) }.toByteArray()
    private fun box(type: String, content: ByteArray) = data { writeInt(content.size + 8); writeBytes(type); write(content) }
    private fun descriptor(tag: Int, content: ByteArray) = byteArrayOf(tag.toByte(), content.size.toByte()) + content
    private fun fixture(delta: Long = 1024, sparse: Boolean = false, editTicks: Int? = null, fixed: Boolean = false): File {
        fun movie(offset: Long): ByteArray {
            val config = descriptor(3, byteArrayOf(0, 1, 0) + descriptor(4,
                byteArrayOf(0x40, 0x15) + ByteArray(11) + descriptor(5, byteArrayOf(0x13, 0x90.toByte()))))
            val stsd = box("stsd", data { writeInt(0); writeInt(1); write(box("mp4a", ByteArray(28) + box("esds", ByteArray(4) + config))) })
            val stts = box("stts", data { writeInt(0); writeInt(1); writeInt(3); writeInt(delta.toInt()) })
            val stsc = box("stsc", data { writeInt(0); writeInt(1); writeInt(1); writeInt(3); writeInt(1) })
            val stsz = box("stsz", data { writeInt(0); writeInt(if (fixed) 1 else 0); writeInt(3); if (!fixed) repeat(3) { writeInt(1) } })
            val stco = box(if (sparse) "co64" else "stco", data { writeInt(0); writeInt(1); if (sparse) writeLong(offset) else writeInt(offset.toInt()) })
            val mdhd = box("mdhd", data { writeInt(0x01000000); writeLong(0); writeLong(0); writeInt(22_050); writeLong(delta * 3); writeInt(0) })
            val hdlr = box("hdlr", ByteArray(8) + "soun".toByteArray() + ByteArray(12))
            val mdia = box("mdia", mdhd + hdlr + box("minf", box("stbl", stsd + stts + stsc + stsz + stco)))
            val edits = editTicks?.let { box("edts", box("elst", data { writeInt(0); writeInt(1); writeInt(1000); writeInt(it); writeInt(0x10000) })) } ?: byteArrayOf()
            val mvhd = box("mvhd", data { writeInt(0); writeInt(0); writeInt(0); writeInt(1000); writeInt(1000) })
            return box("moov", mvhd + box("trak", edits + mdia))
        }
        val first = movie(0)
        val offset = if (sparse) (1L shl 31) + 4096 else first.size + 16L
        val header = movie(offset)
        val file = File.createTempFile("readalong-aac-", ".m4b")
        RandomAccessFile(file, "rw").use {
            it.write(header)
            it.writeInt(1); it.writeBytes("mdat"); it.writeLong(offset + 3 - header.size)
            it.seek(offset); it.write(byteArrayOf(11, 22, 33))
        }
        return file
    }

    @Test fun seeksByPacketAndKeepsNegativePriming() {
        val file = fixture(editTicks = 1024)
        try { RandomAccessFile(file, "r").use { source ->
            val index = AacMp4Index(source.channel)
            assertArrayEquals(byteArrayOf(0x13, 0x90.toByte()), index.audioSpecificConfig)
            index.seek(-1_000_000)
            assertEquals(-46_439L, index.next()!!.timeUs)
            assertEquals(0L, index.next()!!.timeUs)
            index.seek(0)
            assertArrayEquals(byteArrayOf(22), index.next()!!.bytes)
            assertEquals(46_439L, index.next()!!.timeUs)
            assertNull(index.next())
        } } finally { file.delete() }
    }

    @Test fun packetClockCrossesSigned32BitSamplesAt27Hours() {
        val delta = 1L shl 31
        val file = fixture(delta = delta)
        try { RandomAccessFile(file, "r").use { source ->
            val index = AacMp4Index(source.channel)
            val at27Hours = delta * 1_000_000 / 22_050
            assertTrue(at27Hours > 27L * 60 * 60 * 1_000_000)
            index.seek(at27Hours + 100_000)
            val packet = index.next()!!
            assertEquals(at27Hours, packet.timeUs)
            assertArrayEquals(byteArrayOf(22), packet.bytes)
            assertTrue(index.next()!!.timeUs > 54L * 60 * 60 * 1_000_000)
        } } finally { file.delete() }
    }

    @Test fun sparseCo64OffsetsBeyond2GBAndFixedSampleSizesStayCorrect() {
        val file = fixture(sparse = true, fixed = true)
        try { RandomAccessFile(file, "r").use { source ->
            val index = AacMp4Index(source.channel)
            index.seek(0)
            assertArrayEquals(byteArrayOf(11), index.next()!!.bytes)
            index.seek(50_000)
            assertArrayEquals(byteArrayOf(22), index.next()!!.bytes)
        } } finally { file.delete() }
    }

    @Test fun providerAssetOffsetIsRespected() {
        val original = fixture()
        val embedded = File.createTempFile("readalong-aac-asset-", ".bin")
        try {
            embedded.writeBytes(ByteArray(257) + original.readBytes() + ByteArray(128))
            RandomAccessFile(embedded, "r").use { source ->
                val index = AacMp4Index(source.channel, 257, original.length())
                index.seek(0)
                assertArrayEquals(byteArrayOf(11), index.next()!!.bytes)
            }
        } finally { original.delete(); embedded.delete() }
    }

    @Test fun truncatedMetadataIsRejectedWithoutAllocatingAnUntrustedCount() {
        val file = fixture()
        try {
            RandomAccessFile(file, "rw").use { it.setLength(30) }
            RandomAccessFile(file, "r").use { source ->
                assertThrows(java.io.IOException::class.java) { AacMp4Index(source.channel) }
            }
        } finally { file.delete() }
    }
}
