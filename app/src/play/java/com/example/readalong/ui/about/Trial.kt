package com.example.readalong.ui.about

/** The Play edition's free trial: every feature for 90 days from the first install, then a reminder to unlock. */
object Trial {
    const val DAYS = 90
    private const val DAY_MS = 86_400_000L

    /** Whole days left, counting today; 0 once the trial is over. A clock set backwards never adds days. */
    fun daysLeft(startMs: Long, nowMs: Long): Int {
        val used = ((nowMs - startMs).coerceAtLeast(0L) / DAY_MS).toInt()
        return (DAYS - used).coerceAtLeast(0)
    }

    fun ended(startMs: Long, nowMs: Long): Boolean = daysLeft(startMs, nowMs) == 0
}
