package com.example.readalong.domain.reading

/** Locale-independent editable audiobook time; keep milliseconds when opening an existing anchor. */
fun syncTime(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return "%d:%02d:%02d.%03d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60, milliseconds % 1000)
}

fun parseSyncTime(value: String): Long? {
    val match = Regex("(?:(\\d{1,5}):)?(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?").matchEntire(value.trim()) ?: return null
    val (hours, minutes, seconds, fraction) = match.destructured
    val m = minutes.toLong(); val s = seconds.toLong()
    if (m >= 60 || s >= 60) return null
    return ((hours.toLongOrNull() ?: 0) * 3600 + m * 60 + s) * 1000 + fraction.padEnd(3, '0').toLong()
}
