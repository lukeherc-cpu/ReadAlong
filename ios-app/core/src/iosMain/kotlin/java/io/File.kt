package java.io

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.*

/** java.io.File over the iOS file system: paths are plain POSIX paths inside the app's sandbox. */
class File(path: String) : Comparable<File> {
    constructor(parent: File?, child: String) : this(if (parent == null) child else join(parent.path, child))
    constructor(parent: String?, child: String) : this(if (parent == null) child else join(parent, child))

    val path: String = normalize(path)
    val name: String get() = path.substringAfterLast('/')
    // Java returns null at the root; the apps never ask a root for its parent, and Kotlin sees Java's as non-null.
    val parent: String get() = path.substringBeforeLast('/', "").ifEmpty { if (path.startsWith("/")) "/" else "." }
    val parentFile: File get() = File(parent)
    val absolutePath: String get() = if (path.startsWith("/")) path else join(NSFileManager.defaultManager.currentDirectoryPath, path)
    val absoluteFile: File get() = File(absolutePath)
    val canonicalPath: String get() = (absolutePath as NSString).stringByStandardizingPath
    val canonicalFile: File get() = File(canonicalPath)
    val isAbsolute: Boolean get() = path.startsWith("/")
    val isFile: Boolean get() = stat()?.let { (it.mode and S_IFMT.toUInt()) == S_IFREG.toUInt() } ?: false
    val isDirectory: Boolean get() = stat()?.let { (it.mode and S_IFMT.toUInt()) == S_IFDIR.toUInt() } ?: false
    val isHidden: Boolean get() = name.startsWith(".")
    val extension: String get() = name.substringAfterLast('.', "")
    val nameWithoutExtension: String get() = name.substringBeforeLast('.')

    private class Stat(val mode: UInt, val size: Long, val mtimeMs: Long)
    private fun stat(): Stat? = memScoped {
        val st = alloc<stat>()
        if (platform.posix.stat(path, st.ptr) != 0) null
        else Stat(st.st_mode.toUInt(), st.st_size, st.st_mtimespec.tv_sec * 1000 + st.st_mtimespec.tv_nsec / 1_000_000)
    }

    fun exists(): Boolean = access(path, F_OK) == 0
    fun canRead(): Boolean = access(path, R_OK) == 0
    fun canWrite(): Boolean = access(path, W_OK) == 0
    fun length(): Long = stat()?.takeIf { isFile }?.size ?: 0L
    fun lastModified(): Long = stat()?.mtimeMs ?: 0L
    fun setLastModified(time: Long): Boolean =
        NSFileManager.defaultManager.setAttributes(mapOf(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(time / 1000.0)), path, null)
    fun delete(): Boolean = if (isDirectory) rmdir(path) == 0 else unlink(path) == 0
    fun mkdir(): Boolean = mkdir(path, 0x1ed.convert()) == 0
    fun mkdirs(): Boolean = if (isDirectory) false else NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
    fun renameTo(dest: File): Boolean = rename(path, dest.path) == 0
    fun setReadable(@Suppress("UNUSED_PARAMETER") readable: Boolean, @Suppress("UNUSED_PARAMETER") ownerOnly: Boolean = true) = true
    fun setWritable(@Suppress("UNUSED_PARAMETER") writable: Boolean, @Suppress("UNUSED_PARAMETER") ownerOnly: Boolean = true) = true
    fun setExecutable(@Suppress("UNUSED_PARAMETER") executable: Boolean, @Suppress("UNUSED_PARAMETER") ownerOnly: Boolean = true) = true
    fun createNewFile(): Boolean {
        val fd = open(path, O_CREAT or O_EXCL or O_WRONLY, 0x1a4)
        if (fd < 0) return false
        close(fd); return true
    }
    fun list(): Array<String>? = memScoped {
        val dir = opendir(path) ?: return null
        val names = ArrayList<String>()
        try {
            while (true) {
                val entry = readdir(dir) ?: break
                val n = entry.pointed.d_name.toKString()
                if (n != "." && n != "..") names += n
            }
        } finally { closedir(dir) }
        names.toTypedArray()
    }
    fun listFiles(): Array<File>? = list()?.map { File(this, it) }?.toTypedArray()
    fun listFiles(filter: (File) -> Boolean): Array<File>? = listFiles()?.filter(filter)?.toTypedArray()
    fun usableSpace(): Long = freeSpace
    val freeSpace: Long get() = (NSFileManager.defaultManager.attributesOfFileSystemForPath(path, null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: 0L
    val usableSpace: Long get() = freeSpace
    fun toURI(): String = "file://$absolutePath"

    override fun compareTo(other: File) = path.compareTo(other.path)
    override fun equals(other: Any?) = other is File && other.path == path
    override fun hashCode() = path.hashCode()
    override fun toString() = path

    companion object {
        const val separator = "/"
        const val separatorChar = '/'
        const val pathSeparator = ":"
        private fun join(parent: String, child: String) = if (parent.endsWith("/")) parent + child else "$parent/$child"
        private fun normalize(p: String): String {
            if (p.isEmpty()) return p
            val collapsed = p.replace(Regex("/+"), "/")
            return if (collapsed.length > 1) collapsed.removeSuffix("/") else collapsed
        }
        fun createTempFile(prefix: String, suffix: String?, directory: File? = null): File {
            val dir = directory?.path ?: NSTemporaryDirectory().removeSuffix("/")
            while (true) {
                val f = File(dir, prefix + kotlin.random.Random.nextLong().toULong().toString(36) + (suffix ?: ".tmp"))
                if (f.createNewFile()) return f
            }
        }
    }
}

/** A POSIX file descriptor shared by the file streams, RandomAccessFile and FileChannel. */
class FileDescriptor internal constructor(internal val fd: Int) {
    internal var closed = false
    fun valid() = !closed && fd >= 0
    internal fun close() { if (!closed) { closed = true; platform.posix.close(fd) } }
    fun sync() { fsync(fd) }
}

internal fun openFd(path: String, flags: Int): FileDescriptor {
    val fd = open(path, flags or O_CLOEXEC, 0x1a4)
    if (fd < 0) throw FileNotFoundException("$path: ${strerror(errno)?.toKString()}")
    return FileDescriptor(fd)
}

internal fun preadFd(fd: Int, b: ByteArray, off: Int, len: Int, at: Long): Int {
    if (len == 0) return 0
    val n = b.usePinned { p -> if (at < 0) read(fd, p.addressOf(off), len.convert()) else pread(fd, p.addressOf(off), len.convert(), at) }
    if (n < 0) throw IOException("read: ${strerror(errno)?.toKString()}")
    return if (n == 0L) -1 else n.toInt()
}

internal fun pwriteFd(fd: Int, b: ByteArray, off: Int, len: Int, at: Long) {
    var done = 0
    while (done < len) {
        val n = b.usePinned { p -> if (at < 0) write(fd, p.addressOf(off + done), (len - done).convert()) else pwrite(fd, p.addressOf(off + done), (len - done).convert(), at + done) }
        if (n < 0) throw IOException("write: ${strerror(errno)?.toKString()}")
        done += n.toInt()
    }
}

class FileInputStream private constructor(val fd: FileDescriptor) : InputStream() {
    constructor(file: File) : this(openFd(file.path, O_RDONLY))
    constructor(path: String) : this(File(path))
    override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
    override fun read(b: ByteArray, off: Int, len: Int): Int = preadFd(fd.fd, b, off, len, -1)
    override fun skip(n: Long): Long { val before = lseek(fd.fd, 0, SEEK_CUR); val after = lseek(fd.fd, n, SEEK_CUR); return after - before }
    override fun available(): Int = memScoped {
        val st = alloc<stat>(); fstat(fd.fd, st.ptr)
        (st.st_size - lseek(fd.fd, 0, SEEK_CUR)).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }
    val channel: java.nio.channels.FileChannel get() = java.nio.channels.FileChannel(fd, readable = true, writable = false)
    override fun close() = fd.close()
}

class FileOutputStream private constructor(val fd: FileDescriptor) : OutputStream() {
    constructor(file: File, append: Boolean = false) : this(openFd(file.path, O_WRONLY or O_CREAT or (if (append) O_APPEND else O_TRUNC)))
    constructor(path: String, append: Boolean = false) : this(File(path), append)
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) = pwriteFd(fd.fd, b, off, len, -1)
    val channel: java.nio.channels.FileChannel get() = java.nio.channels.FileChannel(fd, readable = false, writable = true)
    fun getFD() = fd
    override fun close() = fd.close()
}

class RandomAccessFile(file: File, mode: String) : Closeable {
    constructor(path: String, mode: String) : this(File(path), mode)
    private val fd = openFd(file.path, if ("w" in mode) O_RDWR or O_CREAT else O_RDONLY)
    private var pointer = 0L
    val channel: java.nio.channels.FileChannel = java.nio.channels.FileChannel(fd, readable = true, writable = "w" in mode)
    fun length(): Long = memScoped { val st = alloc<stat>(); fstat(fd.fd, st.ptr); st.st_size }
    fun setLength(newLength: Long) { if (ftruncate(fd.fd, newLength) != 0) throw IOException("truncate failed"); if (pointer > newLength) pointer = newLength }
    fun seek(pos: Long) { pointer = pos }
    fun getFilePointer() = pointer
    fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
    fun read(b: ByteArray) = read(b, 0, b.size)
    fun read(b: ByteArray, off: Int, len: Int): Int = preadFd(fd.fd, b, off, len, pointer).also { if (it > 0) pointer += it }
    fun readFully(b: ByteArray, off: Int = 0, len: Int = b.size) {
        var n = 0
        while (n < len) { val r = read(b, off + n, len - n); if (r < 0) throw EOFException(); n += r }
    }
    fun readInt(): Int { val b = ByteArray(4); readFully(b); return ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff) }
    fun write(b: ByteArray) = write(b, 0, b.size)
    fun write(b: ByteArray, off: Int, len: Int) { pwriteFd(fd.fd, b, off, len, pointer); pointer += len }
    fun write(b: Int) = write(byteArrayOf(b.toByte()))
    fun writeBytes(s: String) = write(ByteArray(s.length) { s[it].code.toByte() })
    fun writeLong(v: Long) { writeInt((v ushr 32).toInt()); writeInt(v.toInt()) }
    fun writeShort(v: Int) = write(byteArrayOf((v ushr 8).toByte(), v.toByte()))
    fun writeInt(v: Int) = write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
    fun getFD() = fd
    override fun close() = fd.close()
}
