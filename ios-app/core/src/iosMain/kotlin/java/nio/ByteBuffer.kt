// The java.nio subset the phone's shared code uses, for iOS: heap byte buffers and file channels.
package java.nio

class ByteOrder private constructor(private val name: String) {
    override fun toString() = name
    companion object {
        val BIG_ENDIAN = ByteOrder("BIG_ENDIAN")
        val LITTLE_ENDIAN = ByteOrder("LITTLE_ENDIAN")
        fun nativeOrder() = LITTLE_ENDIAN
    }
}

open class BufferUnderflowException : RuntimeException()
open class BufferOverflowException : RuntimeException()

class ByteBuffer private constructor(private val hb: ByteArray, private val offset: Int, private var cap: Int) {
    private var pos = 0
    private var lim = cap
    private var mark = -1
    private var bigEndian = true

    fun array(): ByteArray = hb
    fun arrayOffset(): Int = offset
    fun hasArray() = true
    fun capacity() = cap
    fun position() = pos
    fun position(newPosition: Int): ByteBuffer { require(newPosition in 0..lim); pos = newPosition; if (mark > pos) mark = -1; return this }
    fun limit() = lim
    fun limit(newLimit: Int): ByteBuffer { require(newLimit in 0..cap); lim = newLimit; if (pos > lim) pos = lim; return this }
    fun remaining() = lim - pos
    fun hasRemaining() = pos < lim
    fun mark(): ByteBuffer { mark = pos; return this }
    fun reset(): ByteBuffer { check(mark >= 0); pos = mark; return this }
    fun clear(): ByteBuffer { pos = 0; lim = cap; mark = -1; return this }
    fun flip(): ByteBuffer { lim = pos; pos = 0; mark = -1; return this }
    fun rewind(): ByteBuffer { pos = 0; mark = -1; return this }
    fun order(): ByteOrder = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
    fun order(order: ByteOrder): ByteBuffer { bigEndian = order == ByteOrder.BIG_ENDIAN; return this }
    fun slice(): ByteBuffer = ByteBuffer(hb, offset + pos, remaining()).also { it.bigEndian = bigEndian }
    fun duplicate(): ByteBuffer = ByteBuffer(hb, offset, cap).also { it.pos = pos; it.lim = lim; it.bigEndian = bigEndian }
    fun compact(): ByteBuffer { hb.copyInto(hb, offset, offset + pos, offset + lim); pos = lim - pos; lim = cap; mark = -1; return this }

    private fun next(n: Int): Int { if (lim - pos < n) throw BufferUnderflowException(); val at = pos; pos += n; return offset + at }
    private fun nextPut(n: Int): Int { if (lim - pos < n) throw BufferOverflowException(); val at = pos; pos += n; return offset + at }
    private fun check(index: Int, n: Int): Int { if (index < 0 || index + n > lim) throw IndexOutOfBoundsException("$index"); return offset + index }

    private fun readN(at: Int, n: Int): Long {
        var v = 0L
        if (bigEndian) for (i in 0 until n) v = (v shl 8) or (hb[at + i].toLong() and 0xff)
        else for (i in n - 1 downTo 0) v = (v shl 8) or (hb[at + i].toLong() and 0xff)
        return v
    }
    private fun writeN(at: Int, n: Int, value: Long) {
        for (i in 0 until n) {
            val shift = if (bigEndian) (n - 1 - i) * 8 else i * 8
            hb[at + i] = (value ushr shift).toByte()
        }
    }

    fun get(): Byte = hb[next(1)]
    fun get(index: Int): Byte = hb[check(index, 1)]
    fun get(dst: ByteArray): ByteBuffer = get(dst, 0, dst.size)
    fun get(dst: ByteArray, off: Int, len: Int): ByteBuffer { val at = next(len); hb.copyInto(dst, off, at, at + len); return this }
    fun put(b: Byte): ByteBuffer { hb[nextPut(1)] = b; return this }
    fun put(index: Int, b: Byte): ByteBuffer { hb[check(index, 1)] = b; return this }
    fun put(src: ByteArray): ByteBuffer = put(src, 0, src.size)
    fun put(src: ByteArray, off: Int, len: Int): ByteBuffer { val at = nextPut(len); src.copyInto(hb, at, off, off + len); return this }
    fun put(src: ByteBuffer): ByteBuffer { val n = src.remaining(); val at = nextPut(n); val from = src.next(n); src.hb.copyInto(hb, at, from, from + n); return this }

    val short: Short get() = readN(next(2), 2).toShort()
    val int: Int get() = readN(next(4), 4).toInt()
    val long: Long get() = readN(next(8), 8)
    val float: Float get() = Float.fromBits(int)
    val double: Double get() = Double.fromBits(long)
    val char: Char get() = readN(next(2), 2).toInt().toChar()
    fun getShort(index: Int): Short = readN(check(index, 2), 2).toShort()
    fun getInt(index: Int): Int = readN(check(index, 4), 4).toInt()
    fun getLong(index: Int): Long = readN(check(index, 8), 8)
    fun putShort(v: Short): ByteBuffer { writeN(nextPut(2), 2, v.toLong()); return this }
    fun putInt(v: Int): ByteBuffer { writeN(nextPut(4), 4, v.toLong()); return this }
    fun putLong(v: Long): ByteBuffer { writeN(nextPut(8), 8, v); return this }
    fun putFloat(v: Float): ByteBuffer = putInt(v.toRawBits())
    fun putDouble(v: Double): ByteBuffer = putLong(v.toRawBits())
    fun putShort(index: Int, v: Short): ByteBuffer { writeN(check(index, 2), 2, v.toLong()); return this }
    fun putInt(index: Int, v: Int): ByteBuffer { writeN(check(index, 4), 4, v.toLong()); return this }
    fun putLong(index: Int, v: Long): ByteBuffer { writeN(check(index, 8), 8, v); return this }

    override fun toString() = "java.nio.HeapByteBuffer[pos=$pos lim=$lim cap=$cap]"

    companion object {
        fun allocate(capacity: Int) = ByteBuffer(ByteArray(capacity), 0, capacity)
        fun allocateDirect(capacity: Int) = allocate(capacity)
        fun wrap(array: ByteArray) = ByteBuffer(array, 0, array.size)
        fun wrap(array: ByteArray, offset: Int, length: Int) = ByteBuffer(array, 0, array.size).also { it.pos = offset; it.lim = offset + length }
    }
}
