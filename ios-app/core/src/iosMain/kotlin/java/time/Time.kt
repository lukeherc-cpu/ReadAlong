package java.time

import java.time.temporal.ChronoUnit
import platform.Foundation.*

class DateTimeException(message: String?) : RuntimeException(message)

/** An instant on the UTC time line, to millisecond precision (all the shared code needs). */
class Instant private constructor(private val epochMs: Long) : Comparable<Instant> {
    fun toEpochMilli() = epochMs
    val epochSecond: Long get() = Math.floorDiv(epochMs, 1000L)
    fun plusMillis(ms: Long) = Instant(epochMs + ms)
    fun plusSeconds(s: Long) = Instant(epochMs + s * 1000)
    fun minusMillis(ms: Long) = Instant(epochMs - ms)
    fun isBefore(other: Instant) = epochMs < other.epochMs
    fun isAfter(other: Instant) = epochMs > other.epochMs
    fun until(end: Instant, unit: ChronoUnit): Long = (end.epochMs - epochMs) / unit.millis
    fun truncatedTo(unit: ChronoUnit) = Instant(Math.floorDiv(epochMs, unit.millis) * unit.millis)
    override fun compareTo(other: Instant) = epochMs.compareTo(other.epochMs)
    override fun equals(other: Any?) = other is Instant && other.epochMs == epochMs
    override fun hashCode() = epochMs.hashCode()
    override fun toString(): String = formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)).let {
        if (epochMs % 1000 == 0L) it.replace(".000Z", "Z") else it
    }

    companion object {
        private val formatter = NSDateFormatter().apply {
            locale = NSLocale(localeIdentifier = "en_US_POSIX"); timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
            dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        }
        private val parser = NSISO8601DateFormatter()
        private val parserFractional = NSISO8601DateFormatter().apply { formatOptions = NSISO8601DateFormatWithInternetDateTime or NSISO8601DateFormatWithFractionalSeconds }
        val EPOCH = Instant(0)
        fun now() = Instant((NSDate().timeIntervalSince1970 * 1000).toLong())
        fun ofEpochMilli(ms: Long) = Instant(ms)
        fun ofEpochSecond(s: Long) = Instant(s * 1000)
        fun parse(text: CharSequence): Instant {
            val s = text.toString()
            val date = parser.dateFromString(s) ?: parserFractional.dateFromString(s) ?: throw DateTimeException("Text '$s' could not be parsed")
            return Instant(kotlin.math.round(date.timeIntervalSince1970 * 1000).toLong())
        }
    }
}

class Duration private constructor(private val ms: Long) : Comparable<Duration> {
    fun toMillis() = ms
    val seconds: Long get() = ms / 1000
    fun getSeconds() = seconds
    override fun compareTo(other: Duration) = ms.compareTo(other.ms)
    companion object {
        val ZERO = Duration(0)
        fun ofMillis(ms: Long) = Duration(ms)
        fun ofSeconds(s: Long) = Duration(s * 1000)
        fun ofMinutes(m: Long) = Duration(m * 60_000)
        fun ofHours(h: Long) = Duration(h * 3_600_000)
        fun between(a: Instant, b: Instant) = Duration(b.toEpochMilli() - a.toEpochMilli())
    }
}

/** Only `LocalDateTime.now()` in log lines: ISO local time text. */
class LocalDateTime private constructor(private val text: String) {
    override fun toString() = text
    companion object {
        fun now(): LocalDateTime = LocalDateTime(NSDateFormatter().apply {
            locale = NSLocale(localeIdentifier = "en_US_POSIX"); dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS"
        }.stringFromDate(NSDate()))
    }
}

private object Math { fun floorDiv(a: Long, b: Long): Long { val q = a / b; return if ((a % b != 0L) && ((a xor b) < 0)) q - 1 else q } }
