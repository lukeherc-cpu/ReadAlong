package com.example.readalong.audible

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Turns a downloaded AAX or AAXC file into a plain M4B, in place and without FFmpeg.
 *
 * Both formats are ordinary MP4 files whose audio track is marked "aavd" instead of "mp4a". Each audio
 * sample is AES-128-CBC encrypted from its first byte with the same key and IV, and the bytes after the
 * last whole 16-byte block are left plain. Decrypting therefore never changes a sample's size, so no
 * offsets move: the samples are decrypted where they are, the track is renamed to "mp4a", the key box
 * ("adrm") is blanked to "free", and the file's brand becomes M4B. Chapters, cover and tags are kept.
 *
 * AAXC carries its key and IV in the download licence. AAX derives them from the account's activation
 * bytes and the file's "adrm" box (the derivation FFmpeg's mov demuxer documents).
 */
object AudibleDecrypter {
    private val AAX_FIXED_KEY = "77214d4b196a87cd520045fd20a51d67".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Decrypts [file] in place. [onProgress] gets the fraction done and may throw to cancel. */
    fun decrypt(file: File, license: AudibleLicense, onProgress: (Float) -> Unit = {}) {
        if (license.format == AudibleLicense.Format.MP3) return
        RandomAccessFile(file, "rw").use { raf ->
            val mp4 = Mp4(raf.channel)
            val tracks = mp4.encryptedTracks()
            if (tracks.isEmpty()) throw IOException("This download isn't an encrypted Audible file.")
            val (key, iv) = when (license.format) {
                AudibleLicense.Format.AAXC -> requireNotNull(license.key) to requireNotNull(license.iv)
                else -> aaxKey(mp4.adrm(tracks.first()) ?: throw IOException("This AAX file has no key box."), requireNotNull(license.activationBytes))
            }
            val total = tracks.sumOf { mp4.sampleBytes(it) }.coerceAtLeast(1)
            var done = 0L
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            val secret = SecretKeySpec(key, "AES")
            val spec = IvParameterSpec(iv)
            for (track in tracks) mp4.forEachChunk(track) { offset, sizes ->
                val buffer = ByteBuffer.allocate(sizes.sum())
                mp4.read(offset, buffer)
                val bytes = buffer.array()
                var position = 0
                for (size in sizes) {
                    val whole = size / 16 * 16
                    if (whole > 0) {
                        cipher.init(Cipher.DECRYPT_MODE, secret, spec)
                        cipher.doFinal(bytes, position, whole, bytes, position)
                    }
                    position += size
                }
                mp4.write(offset, ByteBuffer.wrap(bytes))
                done += bytes.size
                onProgress(done.toFloat() / total)
            }
            // Only after every sample is plain: a file cut short above still says it's encrypted.
            for (track in tracks) mp4.unlock(track)
            mp4.rebrand()
            raf.channel.force(true)
        }
    }

    /** AAX: file key and IV from the activation bytes and the 56-byte blob in "adrm". */
    internal fun aaxKey(adrm: ByteArray, activationBytes: ByteArray): Pair<ByteArray, ByteArray> {
        if (adrm.size < 8 + 56 + 4 + 20) throw IOException("This AAX file's key box is too short.")
        val blob = adrm.copyOfRange(8, 64)
        val checksum = adrm.copyOfRange(68, 88)
        fun sha1(vararg parts: ByteArray) = MessageDigest.getInstance("SHA-1").run { parts.forEach(::update); digest() }
        val intermediateKey = sha1(AAX_FIXED_KEY, activationBytes)
        val intermediateIv = sha1(AAX_FIXED_KEY, intermediateKey, activationBytes)
        if (!sha1(intermediateKey.copyOf(16), intermediateIv.copyOf(16)).contentEquals(checksum))
            throw AudibleException("This AAX file belongs to a different Audible account.")
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(intermediateKey.copyOf(16), "AES"), IvParameterSpec(intermediateIv.copyOf(16)))
        val plain = cipher.doFinal(blob, 0, 48)
        if ((0 until 4).any { activationBytes[it] != plain[3 - it] }) throw AudibleException("This AAX file's key didn't unlock.")
        val fileKey = plain.copyOfRange(8, 24)
        val fileIv = sha1(plain.copyOfRange(26, 42), fileKey, AAX_FIXED_KEY).copyOf(16)
        return fileKey to fileIv
    }

    internal class Box(val type: String, val start: Long, val data: Long, val end: Long)
    internal class Track(val entry: Box, val stbl: Box)

    /** Just enough MP4 to find an encrypted audio track's sample table. Reads tables, never media. */
    internal class Mp4(private val channel: FileChannel) {
        private val size = channel.size()

        fun read(position: Long, buffer: ByteBuffer) {
            var at = position
            while (buffer.hasRemaining()) { val count = channel.read(buffer, at); if (count < 0) throw IOException("The download is incomplete."); at += count }
            buffer.flip()
        }
        fun write(position: Long, buffer: ByteBuffer) { var at = position; while (buffer.hasRemaining()) at += channel.write(buffer, at) }
        private fun bytes(position: Long, count: Int) = ByteBuffer.allocate(count).also { read(position, it) }

        fun boxes(start: Long, end: Long): List<Box> {
            val result = mutableListOf<Box>()
            var at = start
            while (at + 8 <= end) {
                val header = bytes(at, 8)
                var length = header.int.toLong() and 0xffffffffL
                val type = String(ByteArray(4).also(header::get), Charsets.ISO_8859_1)
                var data = at + 8
                if (length == 1L) { length = bytes(at + 8, 8).long; data += 8 } else if (length == 0L) length = end - at
                if (length < data - at || at + length > end) throw IOException("The download is damaged (bad \"$type\" box).")
                result += Box(type, at, data, at + length)
                at += length
            }
            return result
        }
        private fun child(box: Box?, type: String) = box?.let { boxes(it.data, it.end).firstOrNull { child -> child.type == type } }

        fun encryptedTracks(): List<Track> {
            val moov = boxes(0, size).firstOrNull { it.type == "moov" } ?: throw IOException("The download has no movie header.")
            return boxes(moov.data, moov.end).filter { it.type == "trak" }.mapNotNull { trak ->
                val stbl = child(child(child(trak, "mdia"), "minf"), "stbl") ?: return@mapNotNull null
                val stsd = child(stbl, "stsd") ?: return@mapNotNull null
                // stsd: version/flags, entry count, then sample entries.
                boxes(stsd.data + 8, stsd.end).firstOrNull { it.type == "aavd" }?.let { Track(it, stbl) }
            }
        }

        /** The sample entry's child boxes start after the audio fields, whose length depends on the sound description version. */
        private fun entryChildren(entry: Box): List<Box> {
            val version = bytes(entry.data + 8, 2).short.toInt()
            val fields = when (version) { 1 -> 28 + 16; 2 -> 28 + 36; else -> 28 }
            return boxes(entry.data + fields, entry.end)
        }

        fun adrm(track: Track) = entryChildren(track.entry).firstOrNull { it.type == "adrm" }?.let { bytes(it.data, Math.toIntExact(it.end - it.data)).array() }

        fun unlock(track: Track) {
            write(track.entry.start + 4, ByteBuffer.wrap("mp4a".toByteArray(Charsets.ISO_8859_1)))
            for (box in entryChildren(track.entry)) if (box.type == "adrm" || box.type == "aabd")
                write(box.start + 4, ByteBuffer.wrap("free".toByteArray(Charsets.ISO_8859_1)))
        }

        /** "aax " and "aaxc" brands make players refuse the file; M4B is what it now is. */
        fun rebrand() {
            val ftyp = boxes(0, size).firstOrNull { it.type == "ftyp" } ?: return
            write(ftyp.data, ByteBuffer.wrap("M4B ".toByteArray(Charsets.ISO_8859_1)))
            var at = ftyp.data + 8
            while (at + 4 <= ftyp.end) {
                val brand = String(bytes(at, 4).array(), Charsets.ISO_8859_1)
                if (brand.startsWith("aa")) write(at, ByteBuffer.wrap("M4A ".toByteArray(Charsets.ISO_8859_1)))
                at += 4
            }
        }

        private fun sampleSizes(track: Track): IntArray {
            val stsz = child(track.stbl, "stsz") ?: throw IOException("The download has no sample sizes.")
            val header = bytes(stsz.data + 4, 8)
            val constant = header.int
            val count = header.int
            if (count < 0 || (constant == 0 && stsz.data + 12 + 4L * count > stsz.end)) throw IOException("The download's sample table is damaged.")
            if (constant != 0) return IntArray(count) { constant }
            val table = bytes(stsz.data + 12, 4 * count)
            return IntArray(count) { table.int }
        }

        fun sampleBytes(track: Track) = sampleSizes(track).sumOf { it.toLong() }

        /** Calls [action] with each chunk's file offset and the sizes of its samples, in file order. */
        fun forEachChunk(track: Track, action: (Long, IntArray) -> Unit) {
            val sizes = sampleSizes(track)
            val offsets = child(track.stbl, "stco")?.let { box ->
                val count = bytes(box.data + 4, 4).int
                val table = bytes(box.data + 8, 4 * count)
                LongArray(count) { table.int.toLong() and 0xffffffffL }
            } ?: child(track.stbl, "co64")?.let { box ->
                val count = bytes(box.data + 4, 4).int
                val table = bytes(box.data + 8, 8 * count)
                LongArray(count) { table.long }
            } ?: throw IOException("The download has no chunk offsets.")
            val stsc = child(track.stbl, "stsc") ?: throw IOException("The download has no chunk table.")
            val runs = bytes(stsc.data + 4, 4).int
            val table = bytes(stsc.data + 8, 12 * runs)
            val firstChunk = IntArray(runs)
            val perChunk = IntArray(runs)
            for (index in 0 until runs) { firstChunk[index] = table.int; perChunk[index] = table.int; table.int }
            if (runs == 0 || firstChunk[0] != 1) throw IOException("The download's chunk table is damaged.")
            var sample = 0
            var run = 0
            for (chunk in offsets.indices) {
                while (run + 1 < runs && firstChunk[run + 1] <= chunk + 1) run++
                val count = minOf(perChunk[run], sizes.size - sample)
                if (count <= 0) break
                action(offsets[chunk], sizes.copyOfRange(sample, sample + count))
                sample += count
            }
        }
    }
}
