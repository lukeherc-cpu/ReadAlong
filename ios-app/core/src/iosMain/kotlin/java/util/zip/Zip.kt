// java.util.zip over the system zlib, for reading EPUBs and writing generated ones.
package java.util.zip

import java.io.*
import java.util.Enumeration
import kotlinx.cinterop.*
import platform.zlib.*

open class ZipException(message: String?) : IOException(message)
class DataFormatException(message: String?) : Exception(message)

interface Checksum { fun update(b: Int); fun update(b: ByteArray, off: Int, len: Int); val value: Long; fun reset() }

class CRC32 : Checksum {
    private var crc = 0UL
    override fun update(b: Int) = update(byteArrayOf(b.toByte()), 0, 1)
    fun update(b: ByteArray) = update(b, 0, b.size)
    override fun update(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        crc = b.usePinned { crc32(crc.convert(), it.addressOf(off).reinterpret(), len.convert()).convert() }
    }
    override val value: Long get() = crc.toLong()
    fun getValue() = value
    override fun reset() { crc = 0UL }
}

open class ZipEntry(val name: String) {
    var method: Int = -1
    var size: Long = -1
    var compressedSize: Long = -1
    var crc: Long = -1
    var time: Long = -1
    var comment: String? = null
    internal var localHeaderOffset = 0L
    val isDirectory: Boolean get() = name.endsWith("/")
    override fun toString() = name
    companion object {
        const val STORED = 0
        const val DEFLATED = 8
    }
}

private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
private fun u32(b: ByteArray, at: Int) = (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

/** Reads the central directory (zip64 is not needed for EPUBs) and streams each entry through inflate. */
class ZipFile(file: File) : Closeable {
    constructor(path: String) : this(File(path))
    private val raf = RandomAccessFile(file, "r")
    private val map = LinkedHashMap<String, ZipEntry>()

    init {
        try { readDirectory() } catch (e: IOException) { raf.close(); throw e } catch (e: RuntimeException) { raf.close(); throw ZipException(e.message ?: "zip END header not found") }
    }

    private fun readAt(pos: Long, len: Int): ByteArray { val b = ByteArray(len); raf.seek(pos); raf.readFully(b); return b }

    private fun readDirectory() {
        val length = raf.length()
        val tailLen = minOf(length, 65_557L).toInt()
        val tail = readAt(length - tailLen, tailLen)
        var eocd = -1
        for (i in tailLen - 22 downTo 0) if (u32(tail, i) == 0x06054b50L) { eocd = i; break }
        if (eocd < 0) throw ZipException("zip END header not found")
        val count = u16(tail, eocd + 10)
        val cdSize = u32(tail, eocd + 12)
        val cdOffset = u32(tail, eocd + 16)
        val cd = readAt(cdOffset, cdSize.toInt())
        var p = 0
        repeat(count) {
            if (u32(cd, p) != 0x02014b50L) throw ZipException("invalid CEN header")
            val flags = u16(cd, p + 8)
            val method = u16(cd, p + 10)
            val crc = u32(cd, p + 16)
            val csize = u32(cd, p + 20)
            val usize = u32(cd, p + 24)
            val nlen = u16(cd, p + 28); val xlen = u16(cd, p + 30); val clen = u16(cd, p + 32)
            val offset = u32(cd, p + 42)
            val nameBytes = cd.copyOfRange(p + 46, p + 46 + nlen)
            val name = if (flags and 0x800 != 0) nameBytes.decodeToString() else nameBytes.decodeToString()
            map[name] = ZipEntry(name).also { it.method = method; it.crc = crc; it.compressedSize = csize; it.size = usize; it.localHeaderOffset = offset }
            p += 46 + nlen + xlen + clen
        }
    }

    fun entries(): Enumeration<out ZipEntry> {
        val it = map.values.iterator()
        return object : Enumeration<ZipEntry> {
            override fun hasMoreElements() = it.hasNext()
            override fun nextElement() = it.next()
        }
    }
    fun size() = map.size
    fun getEntry(name: String): ZipEntry? = map[name]

    fun getInputStream(entry: ZipEntry): InputStream {
        val e = map[entry.name] ?: throw ZipException("no such entry ${entry.name}")
        val header = readAt(e.localHeaderOffset, 30)
        if (u32(header, 0) != 0x04034b50L) throw ZipException("invalid LOC header")
        val dataAt = e.localHeaderOffset + 30 + u16(header, 26) + u16(header, 28)
        val raw = readAt(dataAt, e.compressedSize.toInt())
        return when (e.method) {
            ZipEntry.STORED -> ByteArrayInputStream(raw)
            ZipEntry.DEFLATED -> ByteArrayInputStream(Inflater.inflateRaw(raw, e.size.takeIf { it >= 0 }?.toInt()))
            else -> throw ZipException("unsupported compression method ${e.method}")
        }
    }

    override fun close() = raf.close()
}

internal object Inflater {
    fun inflateRaw(input: ByteArray, expected: Int?): ByteArray = memScoped {
        val strm = alloc<z_stream>()
        if (inflateInit2_(strm.ptr, -15, zlibVersion()?.toKString(), sizeOf<z_stream>().toInt()) != Z_OK) throw ZipException("inflateInit failed")
        val out = ByteArrayOutputStream(expected ?: (input.size * 3))
        val chunk = ByteArray(64 * 1024)
        try {
            input.usePinned { inp ->
                strm.next_in = if (input.isEmpty()) null else inp.addressOf(0).reinterpret()
                strm.avail_in = input.size.convert()
                chunk.usePinned { o ->
                    while (true) {
                        strm.next_out = o.addressOf(0).reinterpret(); strm.avail_out = chunk.size.convert()
                        val rc = inflate(strm.ptr, Z_NO_FLUSH)
                        val produced = chunk.size - strm.avail_out.toInt()
                        if (produced > 0) out.write(chunk, 0, produced)
                        if (rc == Z_STREAM_END) break
                        if (rc != Z_OK && rc != Z_BUF_ERROR) throw ZipException("invalid deflate data ($rc)")
                        if (rc == Z_BUF_ERROR && strm.avail_in.toInt() == 0) break
                    }
                }
            }
        } finally { inflateEnd(strm.ptr) }
        out.toByteArray()
    }

    fun deflateRaw(input: ByteArray): ByteArray = memScoped {
        val strm = alloc<z_stream>()
        if (deflateInit2_(strm.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY, zlibVersion()?.toKString(), sizeOf<z_stream>().toInt()) != Z_OK)
            throw ZipException("deflateInit failed")
        val out = ByteArrayOutputStream(input.size / 2 + 64)
        val chunk = ByteArray(64 * 1024)
        try {
            input.usePinned { inp ->
                strm.next_in = if (input.isEmpty()) null else inp.addressOf(0).reinterpret()
                strm.avail_in = input.size.convert()
                chunk.usePinned { o ->
                    while (true) {
                        strm.next_out = o.addressOf(0).reinterpret(); strm.avail_out = chunk.size.convert()
                        val rc = deflate(strm.ptr, Z_FINISH)
                        val produced = chunk.size - strm.avail_out.toInt()
                        if (produced > 0) out.write(chunk, 0, produced)
                        if (rc == Z_STREAM_END) break
                        if (rc != Z_OK && rc != Z_BUF_ERROR) throw ZipException("deflate failed ($rc)")
                    }
                }
            }
        } finally { deflateEnd(strm.ptr) }
        out.toByteArray()
    }
}

/** Buffers each entry, then writes it with a data-less local header (sizes known up front) and a central directory. */
class ZipOutputStream(private val target: OutputStream) : OutputStream() {
    private val entries = ArrayList<ZipEntry>()
    private var current: ZipEntry? = null
    private val buffer = ByteArrayOutputStream()
    private var written = 0L
    private var finished = false

    fun putNextEntry(e: ZipEntry) { if (current != null) closeEntry(); current = e; buffer.reset() }
    override fun write(b: Int) { checkNotNull(current) { "no current ZIP entry" }; buffer.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) { checkNotNull(current) { "no current ZIP entry" }; buffer.write(b, off, len) }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun out(b: ByteArray) { target.write(b); written += b.size }

    fun closeEntry() {
        val e = current ?: return
        val data = buffer.toByteArray()
        val crc = CRC32().apply { update(data) }.value
        if (e.method == -1) e.method = ZipEntry.DEFLATED
        val stored = if (e.method == ZipEntry.STORED) data else Inflater.deflateRaw(data)
        if (e.method == ZipEntry.STORED && e.crc >= 0 && e.crc != crc) throw ZipException("invalid entry crc-32")
        e.crc = crc; e.size = data.size.toLong(); e.compressedSize = stored.size.toLong(); e.localHeaderOffset = written
        val name = e.name.encodeToByteArray()
        out(le32(0x04034b50) + le16(20) + le16(0x800) + le16(e.method) + le16(0) + le16(0x21) + le32(crc) + le32(e.compressedSize) + le32(e.size) +
            le16(name.size) + le16(0) + name)
        out(stored)
        entries += e; current = null; buffer.reset()
    }

    fun finish() {
        if (finished) return
        closeEntry()
        val cdStart = written
        for (e in entries) {
            val name = e.name.encodeToByteArray()
            out(le32(0x02014b50) + le16(20) + le16(20) + le16(0x800) + le16(e.method) + le16(0) + le16(0x21) + le32(e.crc) + le32(e.compressedSize) +
                le32(e.size) + le16(name.size) + le16(0) + le16(0) + le16(0) + le16(0) + le32(0) + le32(e.localHeaderOffset) + name)
        }
        val cdSize = written - cdStart
        out(le32(0x06054b50) + le16(0) + le16(0) + le16(entries.size) + le16(entries.size) + le32(cdSize) + le32(cdStart) + le16(0))
        finished = true
    }
    override fun flush() = target.flush()
    override fun close() { finish(); target.close() }
}
