package java.nio.file

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel

class Path(val file: File) {
    fun toFile() = file
    val fileName: Path get() = Path(File(file.name))
    val parent: Path? get() = file.parentFile?.let(::Path)
    fun resolve(other: String) = Path(File(file, other))
    override fun toString() = file.path
    override fun equals(other: Any?) = other is Path && other.file == file
    override fun hashCode() = file.hashCode()
}

object Paths { fun get(first: String, vararg more: String) = Path(File(listOf(first, *more).joinToString("/"))) }

class NoSuchFileException(file: String) : java.io.IOException(file)

object Files {
    fun createTempFile(prefix: String, suffix: String?): Path = Path(File.createTempFile(prefix, suffix))
    fun createTempDirectory(prefix: String): Path {
        val f = File.createTempFile(prefix, ""); f.delete(); f.mkdirs(); return Path(f)
    }
    fun write(path: Path, bytes: ByteArray): Path { FileOutputStream(path.file).use { it.write(bytes) }; return path }
    fun readAllBytes(path: Path): ByteArray = FileInputStream(path.file).use { it.readAllBytes() }
    fun newByteChannel(path: Path): SeekableByteChannel = FileChannel.open(path.file, readOnly = true)
    fun delete(path: Path) { if (!path.file.delete()) throw NoSuchFileException(path.toString()) }
    fun deleteIfExists(path: Path): Boolean = path.file.delete()
    fun exists(path: Path) = path.file.exists()
    fun size(path: Path) = path.file.length()
    fun isDirectory(path: Path) = path.file.isDirectory
    fun createDirectories(path: Path): Path { path.file.mkdirs(); return path }
}
