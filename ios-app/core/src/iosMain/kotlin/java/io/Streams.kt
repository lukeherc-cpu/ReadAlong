package java.io

import com.example.readalong.compat.copyTo

abstract class InputStream : Closeable {
    abstract fun read(): Int
    open fun read(b: ByteArray): Int = read(b, 0, b.size)
    open fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var i = 0
        while (i < len) {
            val c = read()
            if (c < 0) return if (i == 0) -1 else i
            b[off + i] = c.toByte(); i++
        }
        return i
    }
    open fun readAllBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        copyTo(out)
        return out.toByteArray()
    }
    open fun readNBytes(n: Int): ByteArray {
        val buf = ByteArray(n); var total = 0
        while (total < n) { val r = read(buf, total, n - total); if (r < 0) break; total += r }
        return if (total == n) buf else buf.copyOf(total)
    }
    open fun skip(n: Long): Long {
        var left = n; val buf = ByteArray(8192)
        while (left > 0) { val r = read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) break; left -= r }
        return n - left
    }
    open fun available(): Int = 0
    open fun transferTo(out: OutputStream): Long = copyTo(out)
    override fun close() {}
}

abstract class OutputStream : Closeable, Flushable {
    abstract fun write(b: Int)
    open fun write(b: ByteArray) = write(b, 0, b.size)
    open fun write(b: ByteArray, off: Int, len: Int) { for (i in off until off + len) write(b[i].toInt()) }
    override fun flush() {}
    override fun close() {}
}

open class FilterInputStream(protected val input: InputStream) : InputStream() {
    override fun read() = input.read()
    override fun read(b: ByteArray, off: Int, len: Int) = input.read(b, off, len)
    override fun available() = input.available()
    override fun close() = input.close()
}
open class FilterOutputStream(protected val out: OutputStream) : OutputStream() {
    override fun write(b: Int) = out.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun flush() = out.flush()
    override fun close() { flush(); out.close() }
}

class BufferedInputStream(input: InputStream, size: Int = 64 * 1024) : FilterInputStream(input) {
    private val buf = ByteArray(size); private var pos = 0; private var count = 0
    private fun fill(): Boolean { pos = 0; count = input.read(buf, 0, buf.size).coerceAtLeast(0); return count > 0 }
    override fun read(): Int = if (pos < count || fill()) buf[pos++].toInt() and 0xff else -1
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= count) {
            if (len >= buf.size) return input.read(b, off, len)
            if (!fill()) return -1
        }
        val n = minOf(len, count - pos)
        buf.copyInto(b, off, pos, pos + n); pos += n
        return n
    }
    override fun available() = (count - pos) + input.available()
}

class BufferedOutputStream(out: OutputStream, size: Int = 64 * 1024) : FilterOutputStream(out) {
    private val buf = ByteArray(size); private var count = 0
    private fun drain() { if (count > 0) { out.write(buf, 0, count); count = 0 } }
    override fun write(b: Int) { if (count == buf.size) drain(); buf[count++] = b.toByte() }
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len >= buf.size) { drain(); out.write(b, off, len); return }
        if (len > buf.size - count) drain()
        b.copyInto(buf, count, off, off + len); count += len
    }
    override fun flush() { drain(); out.flush() }
}

class ByteArrayInputStream(private val data: ByteArray, offset: Int = 0, length: Int = data.size - offset) : InputStream() {
    private var pos = offset; private val end = offset + length
    override fun read(): Int = if (pos < end) data[pos++].toInt() and 0xff else -1
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= end) return -1
        val n = minOf(len, end - pos); data.copyInto(b, off, pos, pos + n); pos += n; return n
    }
    override fun available() = end - pos
}

class ByteArrayOutputStream(initial: Int = 256) : OutputStream() {
    private var buf = ByteArray(maxOf(initial, 16)); private var count = 0
    private fun ensure(extra: Int) { if (count + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, count + extra)) }
    override fun write(b: Int) { ensure(1); buf[count++] = b.toByte() }
    override fun write(b: ByteArray, off: Int, len: Int) { ensure(len); b.copyInto(buf, count, off, off + len); count += len }
    fun writeBytes(b: ByteArray) = write(b, 0, b.size)
    fun size() = count
    fun reset() { count = 0 }
    fun toByteArray(): ByteArray = buf.copyOf(count)
    override fun toString(): String = buf.decodeToString(0, count)
    fun writeTo(out: OutputStream) = out.write(buf, 0, count)
}

class DataInputStream(input: InputStream) : FilterInputStream(input) {
    fun readFully(b: ByteArray) = readFully(b, 0, b.size)
    fun readFully(b: ByteArray, off: Int, len: Int) {
        var n = 0
        while (n < len) { val r = input.read(b, off + n, len - n); if (r < 0) throw EOFException(); n += r }
    }
    private fun u8(): Int { val c = input.read(); if (c < 0) throw EOFException(); return c }
    fun readByte(): Byte = u8().toByte()
    fun readUnsignedByte(): Int = u8()
    fun readBoolean(): Boolean = u8() != 0
    fun readShort(): Short = ((u8() shl 8) or u8()).toShort()
    fun readUnsignedShort(): Int = (u8() shl 8) or u8()
    fun readInt(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
    fun readLong(): Long = (readInt().toLong() shl 32) or (readInt().toLong() and 0xffffffffL)
    fun readUTF(): String { val b = ByteArray(readUnsignedShort()); readFully(b); return b.decodeToString() }
}

class DataOutputStream(out: OutputStream) : FilterOutputStream(out) {
    fun writeByte(v: Int) = out.write(v)
    fun writeBoolean(v: Boolean) = out.write(if (v) 1 else 0)
    fun writeShort(v: Int) { out.write(v ushr 8 and 0xff); out.write(v and 0xff) }
    fun writeInt(v: Int) { out.write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())) }
    fun writeLong(v: Long) { writeInt((v ushr 32).toInt()); writeInt(v.toInt()) }
    fun writeBytes(s: String) { out.write(ByteArray(s.length) { s[it].code.toByte() }) }
    fun writeChars(s: String) { s.forEach { writeShort(it.code) } }
    fun writeUTF(s: String) { val b = s.encodeToByteArray(); require(b.size <= 0xffff); writeShort(b.size); out.write(b) }
}

abstract class Reader : Closeable {
    abstract fun read(buf: CharArray, off: Int, len: Int): Int
    open fun readText(): String { val sb = StringBuilder(); val b = CharArray(8192); while (true) { val n = read(b, 0, b.size); if (n < 0) break; sb.appendRange(b, 0, n) }; return sb.toString() }
    override fun close() {}
}
class StringReader(private val s: String) : Reader() {
    private var pos = 0
    override fun read(buf: CharArray, off: Int, len: Int): Int {
        if (pos >= s.length) return -1
        val n = minOf(len, s.length - pos); s.toCharArray(buf, off, pos, pos + n); pos += n; return n
    }
    override fun readText(): String = s.substring(pos).also { pos = s.length }
}
class StringWriter : Closeable {
    private val sb = StringBuilder()
    fun write(s: String) { sb.append(s) }
    fun append(s: CharSequence): StringWriter = apply { sb.append(s) }
    override fun toString() = sb.toString()
    override fun close() {}
}
class PrintWriter(private val w: StringWriter) : Closeable {
    fun println(s: Any?) { w.write("$s\n") }
    fun print(s: Any?) { w.write("$s") }
    fun flush() {}
    override fun close() {}
}
