package java.nio.file

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import com.example.readalong.compat.copyTo

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

open class FileSystemException(file: String?) : java.io.IOException(file)
class NoSuchFileException(file: String) : FileSystemException(file)
class FileAlreadyExistsException(file: String) : FileSystemException(file)
class AtomicMoveNotSupportedException(file: String) : FileSystemException(file)

interface CopyOption
interface OpenOption
enum class StandardCopyOption : CopyOption { REPLACE_EXISTING, ATOMIC_MOVE, COPY_ATTRIBUTES }
enum class StandardOpenOption : OpenOption { READ, WRITE, APPEND, CREATE, CREATE_NEW, TRUNCATE_EXISTING }

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
    /** rename(2) replaces the target atomically on the same volume, which is every move the apps make. */
    fun move(source: Path, target: Path, vararg options: CopyOption): Path {
        if (!source.file.exists()) throw NoSuchFileException(source.toString())
        if (StandardCopyOption.REPLACE_EXISTING !in options && target.file.exists()) throw FileAlreadyExistsException(target.toString())
        if (!source.file.renameTo(target.file)) throw FileSystemException("Couldn't move $source to $target")
        return target
    }
    fun copy(source: Path, target: Path, vararg options: CopyOption): Path {
        if (StandardCopyOption.REPLACE_EXISTING !in options && target.file.exists()) throw FileAlreadyExistsException(target.toString())
        FileInputStream(source.file).use { input -> FileOutputStream(target.file).use { out -> input.copyTo(out) } }
        return target
    }
    fun size(path: Path) = path.file.length()
    fun isDirectory(path: Path) = path.file.isDirectory
    fun createDirectories(path: Path): Path { path.file.mkdirs(); return path }
}
