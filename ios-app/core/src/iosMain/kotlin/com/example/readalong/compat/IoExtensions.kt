// Kotlin's JVM-only stream extensions (kotlin.io on Android), for iOS. Every shared file imports this package.
package com.example.readalong.compat

import java.io.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

fun InputStream.copyTo(out: OutputStream, bufferSize: Int = 64 * 1024): Long {
    val buf = ByteArray(bufferSize); var total = 0L
    while (true) { val r = read(buf); if (r < 0) break; out.write(buf, 0, r); total += r }
    return total
}
fun InputStream.readBytes(): ByteArray = readAllBytes()
fun InputStream.buffered(size: Int = 64 * 1024): BufferedInputStream = this as? BufferedInputStream ?: BufferedInputStream(this, size)
fun OutputStream.buffered(size: Int = 64 * 1024): BufferedOutputStream = this as? BufferedOutputStream ?: BufferedOutputStream(this, size)
fun InputStream.reader(): InputStreamText = InputStreamText(this)
fun OutputStream.writer(): OutputStreamText = OutputStreamText(this)

class InputStreamText(private val input: InputStream) : Closeable {
    fun readText(): String = input.readAllBytes().decodeToString()
    override fun close() = input.close()
}
class OutputStreamText(private val out: OutputStream) : Closeable, Flushable {
    fun write(text: String) = out.write(text.encodeToByteArray())
    fun append(text: CharSequence): OutputStreamText = apply { write(text.toString()) }
    override fun flush() = out.flush()
    override fun close() = out.close()
}


/** A reader over a whole stream: the shared code only reads text to the end or line by line. */
class TextReader(private val input: InputStream) : Closeable {
    private val text by lazy { input.readAllBytes().decodeToString() }
    fun readText(): String = text
    fun readLines(): List<String> = text.lines().let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
    fun lineSequence(): Sequence<String> = readLines().asSequence()
    fun <T> useLines(block: (Sequence<String>) -> T): T = use { block(lineSequence()) }
    fun forEachLine(action: (String) -> Unit) = readLines().forEach(action)
    override fun close() = input.close()
}
fun InputStream.bufferedReader(@Suppress("UNUSED_PARAMETER") charset: Charset = Charsets.UTF_8): TextReader = TextReader(this)

fun File.readBytes(): ByteArray = FileInputStream(this).use { it.readAllBytes() }
fun File.readText(@Suppress("UNUSED_PARAMETER") charset: Charset = Charsets.UTF_8): String = readBytes().decodeToString()
fun File.readLines(charset: Charset = Charsets.UTF_8): List<String> = bufferedReader(charset).use { it.readLines() }
fun File.bufferedReader(charset: Charset = Charsets.UTF_8): TextReader = FileInputStream(this).bufferedReader(charset)
fun File.writeBytes(bytes: ByteArray) = FileOutputStream(this).use { it.write(bytes) }
fun File.writeText(text: String, @Suppress("UNUSED_PARAMETER") charset: Charset = Charsets.UTF_8) = writeBytes(text.encodeToByteArray())
fun File.appendText(text: String) = FileOutputStream(this, append = true).use { it.write(text.encodeToByteArray()) }
fun File.appendBytes(bytes: ByteArray) = FileOutputStream(this, append = true).use { it.write(bytes) }
fun File.inputStream(): FileInputStream = FileInputStream(this)
fun File.outputStream(): FileOutputStream = FileOutputStream(this)
fun File.forEachLine(action: (String) -> Unit) = readLines().forEach(action)
fun <T> File.useLines(@Suppress("UNUSED_PARAMETER") charset: Charset = Charsets.UTF_8, block: (Sequence<String>) -> T): T = bufferedReader().useLines(block)
fun ByteArray.inputStream(): ByteArrayInputStream = ByteArrayInputStream(this)
fun ByteArray.inputStream(offset: Int, length: Int): ByteArrayInputStream = ByteArrayInputStream(this, offset, length)
fun String.byteInputStream(): ByteArrayInputStream = ByteArrayInputStream(encodeToByteArray())
fun File.deleteRecursively(): Boolean {
    if (isDirectory) listFiles()?.forEach { it.deleteRecursively() }
    return delete() || !exists()
}
fun File.walk(): Sequence<File> = sequence {
    yield(this@walk)
    if (isDirectory) listFiles()?.sortedBy { it.name }?.forEach { yieldAll(it.walk()) }
}
fun File.walkTopDown() = walk()
fun File.copyTo(target: File, overwrite: Boolean = false, bufferSize: Int = 64 * 1024): File {
    if (target.exists() && !overwrite) throw IOException("The destination file already exists: $target")
    target.parentFile?.mkdirs()
    FileInputStream(this).use { input -> FileOutputStream(target).use { input.copyTo(it, bufferSize) } }
    return target
}
fun File.resolve(relative: String): File = if (relative.startsWith("/")) File(relative) else File(this, relative)
fun File.relativeTo(base: File): File = File(path.removePrefix(base.path).removePrefix("/"))
fun File.toRelativeString(base: File): String = relativeTo(base).path
val File.invariantSeparatorsPath: String get() = path

fun File.toPath(): java.nio.file.Path = java.nio.file.Path(this)
