package java.nio.channels

import java.io.Closeable
import java.io.FileDescriptor
import java.io.File
import java.io.IOException
import java.io.preadFd
import java.io.pwriteFd
import java.nio.ByteBuffer
import kotlinx.cinterop.*
import platform.posix.*

interface Channel : Closeable { fun isOpen(): Boolean }
interface ReadableByteChannel : Channel { fun read(dst: ByteBuffer): Int }
interface WritableByteChannel : Channel { fun write(src: ByteBuffer): Int }
interface ByteChannel : ReadableByteChannel, WritableByteChannel
interface SeekableByteChannel : ByteChannel {
    fun position(): Long
    fun position(newPosition: Long): SeekableByteChannel
    fun size(): Long
    fun truncate(size: Long): SeekableByteChannel
}
class ClosedChannelException : IOException()

/** A file channel over a POSIX descriptor; positional reads and writes use pread and pwrite. */
open class FileChannel internal constructor(private val fd: FileDescriptor, private val readable: Boolean, private val writable: Boolean,
                                            private val ownsFd: Boolean = false) : SeekableByteChannel {
    private var pos = 0L
    override fun isOpen() = fd.valid()
    override fun position() = pos
    override fun position(newPosition: Long): FileChannel { pos = newPosition; return this }
    override fun size(): Long = memScoped { val st = alloc<stat>(); fstat(fd.fd, st.ptr); st.st_size }
    override fun truncate(size: Long): FileChannel { ftruncate(fd.fd, size); if (pos > size) pos = size; return this }
    override fun read(dst: ByteBuffer): Int = read(dst, pos).also { if (it > 0) pos += it }
    fun read(dst: ByteBuffer, position: Long): Int {
        if (!dst.hasRemaining()) return 0
        val n = preadFd(fd.fd, dst.array(), dst.arrayOffset() + dst.position(), dst.remaining(), position)
        if (n > 0) dst.position(dst.position() + n)
        return n
    }
    override fun write(src: ByteBuffer): Int = write(src, pos).also { pos += it }
    fun write(src: ByteBuffer, position: Long): Int {
        val n = src.remaining()
        pwriteFd(fd.fd, src.array(), src.arrayOffset() + src.position(), n, position)
        src.position(src.position() + n)
        return n
    }
    fun force(metaData: Boolean) { fsync(fd.fd) }
    override fun close() { if (ownsFd) fd.close() }

    companion object {
        fun open(path: File, readOnly: Boolean = true): FileChannel {
            val f = if (readOnly) java.io.FileInputStream(path).fd else java.io.RandomAccessFile(path, "rw").getFD()
            return FileChannel(f, readable = true, writable = !readOnly, ownsFd = true)
        }
    }
}
