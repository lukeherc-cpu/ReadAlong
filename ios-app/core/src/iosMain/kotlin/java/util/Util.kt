// The java.util subset the phone's shared code uses, for iOS.
package java.util

import kotlin.io.encoding.Base64 as KBase64
import kotlin.io.encoding.ExperimentalEncodingApi
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/** Locale only selects language rules in the shared code; ROOT and US both mean "no locale-specific rules". */
class Locale private constructor(val language: String, val country: String = "") {
    constructor(language: String, country: String, @Suppress("UNUSED_PARAMETER") variant: String = "") : this(language, country, Unit)
    private constructor(language: String, country: String, @Suppress("UNUSED_PARAMETER") marker: Unit) : this(language) { }
    fun toLanguageTag() = if (country.isEmpty()) language.ifEmpty { "und" } else "$language-$country"
    override fun toString() = if (country.isEmpty()) language else "${language}_$country"
    override fun equals(other: Any?) = other is Locale && other.language == language && other.country == country
    override fun hashCode() = language.hashCode() * 31 + country.hashCode()
    companion object {
        val ROOT = Locale("")
        val US = Locale("en")
        val ENGLISH = Locale("en")
        val UK = Locale("en")
        fun getDefault(): Locale = Locale((NSLocale.preferredLanguages.firstOrNull() as? String)?.substringBefore('-') ?: "en")
        fun forLanguageTag(tag: String) = Locale(tag.substringBefore('-'))
    }
}

interface Enumeration<T> {
    fun hasMoreElements(): Boolean
    fun nextElement(): T
}

class Date(val time: Long = (NSDate().timeIntervalSince1970 * 1000).toLong()) : Comparable<Date> {
    fun getTime() = time
    override fun compareTo(other: Date) = time.compareTo(other.time)
    override fun equals(other: Any?) = other is Date && other.time == time
    override fun hashCode() = time.hashCode()
}

class TimeZone private constructor(val id: String) {
    companion object { fun getTimeZone(id: String) = TimeZone(id) }
}

class UUID(val mostSignificantBits: Long, val leastSignificantBits: Long) {
    override fun toString(): String {
        fun hex(v: Long, digits: Int) = (v and ((1L shl (digits * 4)) - 1).let { if (digits == 16) -1L else it }).toULong().toString(16).padStart(digits, '0')
        val m = mostSignificantBits; val l = leastSignificantBits
        return "${hex(m ushr 32, 8)}-${hex(m ushr 16, 4)}-${hex(m, 4)}-${hex(l ushr 48, 4)}-${hex(l, 12)}"
    }
    override fun equals(other: Any?) = other is UUID && other.mostSignificantBits == mostSignificantBits && other.leastSignificantBits == leastSignificantBits
    override fun hashCode() = (mostSignificantBits xor leastSignificantBits).hashCode()
    companion object {
        fun fromString(name: String): UUID {
            val parts = name.split('-')
            require(parts.size == 5) { "Invalid UUID string: $name" }
            val p = parts.map { it.toULong(16).toLong() }
            return UUID((p[0] shl 32) or (p[1] shl 16) or p[2], (p[3] shl 48) or p[4])
        }
        fun randomUUID(): UUID {
            val b = kotlin.random.Random.nextBytes(16)
            b[6] = (b[6].toInt() and 0x0f or 0x40).toByte(); b[8] = (b[8].toInt() and 0x3f or 0x80).toByte()
            var m = 0L; var l = 0L
            for (i in 0 until 8) m = (m shl 8) or (b[i].toLong() and 0xff)
            for (i in 8 until 16) l = (l shl 8) or (b[i].toLong() and 0xff)
            return UUID(m, l)
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
object Base64 {
    class Encoder internal constructor(private val url: Boolean, private val pad: Boolean) {
        private val codec = if (url) KBase64.UrlSafe else KBase64.Default
        fun withoutPadding() = Encoder(url, false)
        fun encodeToString(src: ByteArray): String = codec.encode(src).let { if (pad) it else it.trimEnd('=') }
        fun encode(src: ByteArray): ByteArray = encodeToString(src).encodeToByteArray()
    }
    class Decoder internal constructor(private val url: Boolean, private val mime: Boolean) {
        fun decode(src: String): ByteArray {
            var s = if (mime) src.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' } else src
            if (url) s = s.replace('-', '+').replace('_', '/')
            s = s.trimEnd('=')
            require(s.none { it.isWhitespace() }) { "Illegal base64 character" }
            s += "=".repeat((4 - s.length % 4) % 4)
            return try { KBase64.Default.decode(s) } catch (e: Exception) { throw IllegalArgumentException(e.message, e) }
        }
        fun decode(src: ByteArray): ByteArray = decode(src.decodeToString())
    }
    fun getEncoder() = Encoder(url = false, pad = true)
    fun getUrlEncoder() = Encoder(url = true, pad = true)
    fun getMimeEncoder() = Encoder(url = false, pad = true)
    fun getDecoder() = Decoder(url = false, mime = false)
    fun getUrlDecoder() = Decoder(url = true, mime = false)
    fun getMimeDecoder() = Decoder(url = false, mime = true)
}

object Objects {
    fun hash(vararg values: Any?) = values.contentHashCode()
    fun equals(a: Any?, b: Any?) = a == b
    fun <T> requireNonNull(value: T?): T = value ?: throw NullPointerException()
}

class ConcurrentModificationException(message: String? = null) : RuntimeException(message)
