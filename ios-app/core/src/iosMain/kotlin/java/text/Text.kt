package java.text

import platform.Foundation.*

object Normalizer {
    enum class Form { NFC, NFD, NFKC, NFKD }
    fun normalize(src: CharSequence, form: Form): String {
        @Suppress("CAST_NEVER_SUCCEEDS") val s = src.toString() as NSString
        return when (form) {
            Form.NFC -> s.precomposedStringWithCanonicalMapping
            Form.NFD -> s.decomposedStringWithCanonicalMapping
            Form.NFKC -> s.precomposedStringWithCompatibilityMapping
            Form.NFKD -> s.decomposedStringWithCompatibilityMapping
        }
    }
    fun isNormalized(src: CharSequence, form: Form) = normalize(src, form) == src.toString()
}

class SimpleDateFormat(pattern: String, @Suppress("UNUSED_PARAMETER") locale: java.util.Locale = java.util.Locale.US) {
    private val formatter = NSDateFormatter().apply {
        dateFormat = pattern
        this.locale = NSLocale(localeIdentifier = "en_US_POSIX")
    }
    var timeZone: java.util.TimeZone? = null
        set(value) { field = value; formatter.timeZone = value?.let { NSTimeZone.timeZoneWithName(it.id) } ?: NSTimeZone.localTimeZone }
    fun format(date: java.util.Date): String = formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(date.time / 1000.0))
    fun parse(text: String): java.util.Date? = formatter.dateFromString(text)?.let { java.util.Date((it.timeIntervalSince1970 * 1000).toLong()) }
}

class ParseException(message: String?, val errorOffset: Int) : Exception(message)
