// The JVM-only Kotlin and Java standard library calls the phone's shared code makes, for iOS. Every copied
// shared file imports this package (see core/build.gradle.kts).
package com.example.readalong.compat

import java.util.Enumeration
import java.util.Locale
import kotlin.concurrent.AtomicInt
import platform.Foundation.timeIntervalSince1970

/** Character sets the shared code names; Kotlin/Native strings encode UTF-8, ISO-8859-1 and US-ASCII here. */
class Charset private constructor(val name: String) {
    fun name() = name
    override fun toString() = name
    companion object {
        fun forName(name: String): Charset = when (name.uppercase().replace("_", "-")) {
            "UTF-8", "UTF8" -> Charsets.UTF_8
            "ISO-8859-1", "LATIN1" -> Charsets.ISO_8859_1
            "US-ASCII", "ASCII" -> Charsets.US_ASCII
            "UTF-16BE" -> Charsets.UTF_16BE
            "UTF-16LE" -> Charsets.UTF_16LE
            "UTF-16" -> Charsets.UTF_16
            else -> throw IllegalArgumentException("Unsupported charset $name")
        }
        internal fun of(name: String) = Charset(name)
    }
}

object Charsets {
    val UTF_8 = Charset.of("UTF-8")
    val ISO_8859_1 = Charset.of("ISO-8859-1")
    val US_ASCII = Charset.of("US-ASCII")
    val UTF_16 = Charset.of("UTF-16")
    val UTF_16BE = Charset.of("UTF-16BE")
    val UTF_16LE = Charset.of("UTF-16LE")
}
typealias StandardCharsets = Charsets

fun String.toByteArray(charset: Charset = Charsets.UTF_8): ByteArray = when (charset) {
    Charsets.UTF_8 -> encodeToByteArray()
    Charsets.ISO_8859_1, Charsets.US_ASCII -> ByteArray(length) { i -> this[i].code.let { if (it > (if (charset == Charsets.US_ASCII) 0x7f else 0xff)) '?'.code else it }.toByte() }
    Charsets.UTF_16LE -> ByteArray(length * 2) { i -> (this[i / 2].code shr (if (i % 2 == 0) 0 else 8)).toByte() }
    else -> ByteArray(length * 2) { i -> (this[i / 2].code shr (if (i % 2 == 0) 8 else 0)).toByte() }
}

fun ByteArray.toString(charset: Charset): String = decode(this, 0, size, charset)
fun String(bytes: ByteArray, charset: Charset): String = decode(bytes, 0, bytes.size, charset)
fun String(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String = decode(bytes, offset, length, charset)
fun String(bytes: ByteArray): String = bytes.decodeToString()
fun String(bytes: ByteArray, offset: Int, length: Int): String = bytes.decodeToString(offset, offset + length)
fun String(chars: CharArray): String = chars.concatToString()
fun String(chars: CharArray, offset: Int, length: Int): String = chars.concatToString(offset, offset + length)

private fun decode(b: ByteArray, off: Int, len: Int, charset: Charset): String = when (charset) {
    Charsets.UTF_8 -> b.decodeToString(off, off + len)
    Charsets.ISO_8859_1 -> CharArray(len) { (b[off + it].toInt() and 0xff).toChar() }.concatToString()
    Charsets.US_ASCII -> CharArray(len) { (b[off + it].toInt() and 0x7f).toChar() }.concatToString()
    Charsets.UTF_16LE -> CharArray(len / 2) { ((b[off + 2 * it].toInt() and 0xff) or ((b[off + 2 * it + 1].toInt() and 0xff) shl 8)).toChar() }.concatToString()
    Charsets.UTF_16 -> {
        // A byte-order mark decides; big-endian without one, as Java does.
        val le = len >= 2 && b[off] == 0xFF.toByte() && b[off + 1] == 0xFE.toByte()
        val be = len >= 2 && b[off] == 0xFE.toByte() && b[off + 1] == 0xFF.toByte()
        val start = if (le || be) 2 else 0
        decode(b, off + start, len - start, if (le) Charsets.UTF_16LE else Charsets.UTF_16BE)
    }
    else -> CharArray(len / 2) { (((b[off + 2 * it].toInt() and 0xff) shl 8) or (b[off + 2 * it + 1].toInt() and 0xff)).toChar() }.concatToString()
}

// Locale-taking case conversion: the shared code passes Locale.ROOT, which is Kotlin's invariant conversion.
fun String.lowercase(@Suppress("UNUSED_PARAMETER") locale: Locale): String = lowercase()
fun String.uppercase(@Suppress("UNUSED_PARAMETER") locale: Locale): String = uppercase()
fun String.capitalize(@Suppress("UNUSED_PARAMETER") locale: Locale): String = replaceFirstChar { it.titlecase() }

fun <T> Enumeration<T>.asSequence(): Sequence<T> = sequence { while (hasMoreElements()) yield(nextElement()) }
fun <T> Enumeration<T>.toList(): List<T> = asSequence().toList()

/** java.lang.Math, for the calls Kotlin's math package names differently. */
object Math {
    fun max(a: Int, b: Int) = kotlin.math.max(a, b)
    fun max(a: Long, b: Long) = kotlin.math.max(a, b)
    fun max(a: Double, b: Double) = kotlin.math.max(a, b)
    fun max(a: Float, b: Float) = kotlin.math.max(a, b)
    fun min(a: Int, b: Int) = kotlin.math.min(a, b)
    fun min(a: Long, b: Long) = kotlin.math.min(a, b)
    fun min(a: Double, b: Double) = kotlin.math.min(a, b)
    fun min(a: Float, b: Float) = kotlin.math.min(a, b)
    fun abs(a: Int) = kotlin.math.abs(a)
    fun abs(a: Long) = kotlin.math.abs(a)
    fun abs(a: Double) = kotlin.math.abs(a)
    fun abs(a: Float) = kotlin.math.abs(a)
    fun round(a: Double): Long = kotlin.math.floor(a + 0.5).toLong()
    fun round(a: Float): Int = kotlin.math.floor(a + 0.5f).toInt()
    fun floor(a: Double) = kotlin.math.floor(a)
    fun ceil(a: Double) = kotlin.math.ceil(a)
    fun sqrt(a: Double) = kotlin.math.sqrt(a)
    fun pow(a: Double, b: Double) = a.let { kotlin.math.exp(b * kotlin.math.ln(it)) }.let { if (a == 0.0 || b == 0.0 || a < 0) kotlin.math.round(0.0) + powExact(a, b) else it }
    private fun powExact(a: Double, b: Double): Double { var r = 1.0; repeat(b.toInt()) { r *= a }; return r }
    fun log(a: Double) = kotlin.math.ln(a)
    fun log10(a: Double) = kotlin.math.log10(a)
    fun exp(a: Double) = kotlin.math.exp(a)
    fun signum(a: Double) = kotlin.math.sign(a)
    fun floorDiv(a: Long, b: Long): Long { val q = a / b; return if ((a % b != 0L) && ((a xor b) < 0)) q - 1 else q }
    fun floorDiv(a: Int, b: Int): Int { val q = a / b; return if ((a % b != 0) && ((a xor b) < 0)) q - 1 else q }
    fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b
    fun floorMod(a: Int, b: Int): Int = a - floorDiv(a, b) * b
    fun addExact(a: Long, b: Long): Long { val r = a + b; if (((a xor r) and (b xor r)) < 0) throw ArithmeticException("long overflow"); return r }
    fun multiplyExact(a: Long, b: Long): Long { val r = a * b; if (a != 0L && (r / a != b || (a == -1L && b == Long.MIN_VALUE))) throw ArithmeticException("long overflow"); return r }
    fun toIntExact(a: Long): Int { if (a.toInt().toLong() != a) throw ArithmeticException("integer overflow"); return a.toInt() }
    fun clamp(v: Long, lo: Long, hi: Long) = v.coerceIn(lo, hi)
    const val PI = kotlin.math.PI
}

object System {
    val err = PrintStreamLike { kotlin.io.println(it.trimEnd()) }
    val out = PrintStreamLike { kotlin.io.println(it.trimEnd()) }
    private val properties = HashMap<String, String>()
    fun setProperty(key: String, value: String): String? = properties.put(key, value)
    fun currentTimeMillis(): Long = (platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong()
    fun nanoTime(): Long = platform.posix.clock_gettime_nsec_np(platform.posix.CLOCK_UPTIME_RAW.toUInt()).toLong()
    fun arraycopy(src: ByteArray, srcPos: Int, dest: ByteArray, destPos: Int, length: Int) { src.copyInto(dest, destPos, srcPos, srcPos + length) }
    fun arraycopy(src: ShortArray, srcPos: Int, dest: ShortArray, destPos: Int, length: Int) { src.copyInto(dest, destPos, srcPos, srcPos + length) }
    fun arraycopy(src: FloatArray, srcPos: Int, dest: FloatArray, destPos: Int, length: Int) { src.copyInto(dest, destPos, srcPos, srcPos + length) }
    fun getProperty(key: String): String? = properties[key] ?: when (key) { "os.name" -> "iOS"; "line.separator" -> "\n"; else -> null }
    fun getProperty(key: String, fallback: String): String = getProperty(key) ?: fallback
    fun lineSeparator() = "\n"
    fun identityHashCode(o: Any?): Int = o?.hashCode() ?: 0
}


object Thread {
    fun sleep(ms: Long) { platform.posix.usleep((ms * 1000).toUInt()) }
    fun interface UncaughtExceptionHandler { fun uncaughtException(thread: CurrentThread, error: Throwable) }
    private var handler: UncaughtExceptionHandler? = null
    fun getDefaultUncaughtExceptionHandler(): UncaughtExceptionHandler? = handler
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    fun setDefaultUncaughtExceptionHandler(h: UncaughtExceptionHandler?) {
        handler = h
        if (h != null) kotlin.native.setUnhandledExceptionHook { error -> h.uncaughtException(CurrentThread, error) }
    }
    fun currentThread() = CurrentThread
    object CurrentThread { val isInterrupted get() = false; fun interrupt() {} ; val name get() = "ios" }
    fun interrupted() = false
}

class InterruptedException(message: String? = null) : Exception(message)

/** String.format over Kotlin: %d %s %f %x %X %02x %,d and widths, which is all the shared code formats. */
fun String.Companion.format(format: String, vararg args: Any?): String = formatJava(format, args)
fun String.Companion.format(@Suppress("UNUSED_PARAMETER") locale: Locale?, format: String, vararg args: Any?): String = formatJava(format, args)
fun String.format(vararg args: Any?): String = formatJava(this, args)
fun String.format(@Suppress("UNUSED_PARAMETER") locale: Locale?, vararg args: Any?): String = formatJava(this, args)

private val spec = Regex("%(\\d+\\$)?([-#+ 0,(]*)(\\d+)?(\\.\\d+)?([a-zA-Z%])")

internal fun formatJava(format: String, args: Array<out Any?>): String {
    var next = 0
    return spec.replace(format) { m ->
        val (indexPart, flags, widthPart, precisionPart, conv) = m.destructured
        if (conv == "%") return@replace "%"
        if (conv == "n") return@replace "\n"
        val arg = if (indexPart.isNotEmpty()) args[indexPart.dropLast(1).toInt() - 1] else args[next++]
        val precision = precisionPart.drop(1).toIntOrNull()
        var body = when (conv) {
            "d" -> (arg as Number).toLong().let { v -> if (',' in flags) group(kotlin.math.abs(v).toString()).let { if (v < 0) "-$it" else it } else v.toString() }
            "x", "X" -> when (arg) {
                is Byte -> (arg.toInt() and 0xff).toString(16)
                is Short -> (arg.toInt() and 0xffff).toString(16)
                is Int -> arg.toUInt().toString(16)
                is Long -> arg.toULong().toString(16)
                else -> (arg as Number).toLong().toString(16)
            }.let { if (conv == "X") it.uppercase() else it }
            "f" -> fixed((arg as Number).toDouble(), precision ?: 6).let { if (',' in flags) group(it.substringBefore('.')) + it.substring(it.indexOf('.').let { i -> if (i < 0) it.length else i }) else it }
            "e" -> (arg as Number).toDouble().toString()
            "s", "S" -> arg.toString().let { if (precision != null) it.take(precision) else it }.let { if (conv == "S") it.uppercase() else it }
            "c" -> arg.toString()
            "b" -> (arg != null && arg != false).toString()
            else -> throw IllegalArgumentException("Unsupported format %$conv")
        }
        if ('+' in flags && conv in setOf("d", "f") && !body.startsWith("-")) body = "+$body"
        val width = widthPart.toIntOrNull() ?: 0
        when {
            body.length >= width -> body
            '-' in flags -> body.padEnd(width)
            '0' in flags && conv in setOf("d", "x", "X", "f") -> if (body.startsWith("-") || body.startsWith("+")) body[0] + body.drop(1).padStart(width - 1, '0') else body.padStart(width, '0')
            else -> body.padStart(width)
        }
    }
}

private fun group(digits: String) = digits.reversed().chunked(3).joinToString(",").reversed()

private fun fixed(v: Double, decimals: Int): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
    val negative = v < 0 || (v == 0.0 && 1.0 / v < 0)
    var factor = 1L; repeat(decimals) { factor *= 10 }
    // Half-up rounding like Java's Formatter, done on the decimal string to avoid binary drift.
    val scaled = kotlin.math.abs(v) * factor
    var units = kotlin.math.floor(scaled + 0.5).toLong()
    if (kotlin.math.abs(scaled - kotlin.math.floor(scaled) - 0.5) < 1e-9) units = kotlin.math.floor(scaled).toLong() + 1
    val whole = units / factor; val frac = units % factor
    val text = if (decimals == 0) whole.toString() else "$whole.${frac.toString().padStart(decimals, '0')}"
    return if (negative && units != 0L) "-$text" else text
}

/**
 * JVM monitors (synchronized blocks and @Synchronized methods) as one app-wide recursive lock. The shared code's
 * critical sections are tiny bookkeeping, and a single reentrant lock can't deadlock the way per-object locks
 * taken in different orders could.
 */
@PublishedApi internal val monitor = platform.Foundation.NSRecursiveLock()
inline fun <R> synchronized(@Suppress("UNUSED_PARAMETER") lock: Any, block: () -> R): R {
    monitor.lock()
    try { return block() } finally { monitor.unlock() }
}

/** Marks a method the build wraps in [synchronized] (engine/build.gradle.kts rewrites `@Synchronized fun`). */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
annotation class Synchronized

/** Kotlin/Native keeps Dispatchers.IO internal; the build points the shared code here instead. */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
val IoDispatcher: kotlinx.coroutines.CoroutineDispatcher by lazy { kotlinx.coroutines.newFixedThreadPoolContext(24, "readalong-io") }

val Any.javaClass: KotlinClassName get() = KotlinClassName(this::class.simpleName ?: "Object", this::class.qualifiedName ?: "Object")
class KotlinClassName(val simpleName: String, val name: String)

fun Throwable.printStackTrace(writer: java.io.PrintWriter) { writer.println(stackTraceToString()) }

class PrintStreamLike(private val write: (String) -> Unit) {
    fun println(s: Any? = "") = write("$s\n")
    fun print(s: Any?) = write("$s")
    fun flush() {}
}

object Character {
    fun isLetter(c: Char) = c.isLetter()
    fun isDigit(c: Char) = c.isDigit()
    fun isLetterOrDigit(c: Char) = c.isLetterOrDigit()
    fun isWhitespace(c: Char) = c.isWhitespace()
    fun isUpperCase(c: Char) = c.isUpperCase()
    fun isLowerCase(c: Char) = c.isLowerCase()
    fun toLowerCase(c: Char) = c.lowercaseChar()
    fun toUpperCase(c: Char) = c.uppercaseChar()
    fun isHighSurrogate(c: Char) = c.isHighSurrogate()
    fun isLowSurrogate(c: Char) = c.isLowSurrogate()
    fun isSurrogate(c: Char) = c.isSurrogate()
}

fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? = get(key) ?: run { put(key, value); null }
fun <K, V> MutableMap<K, V>.computeIfAbsent(key: K, f: (K) -> V): V = getOrPut(key) { f(key) }
fun <K, V> Map<K, V>.getOrDefault(key: K, fallback: V): V = get(key) ?: fallback

val com.fleeksoft.ksoup.nodes.TextNode.wholeText: String get() = getWholeText()
