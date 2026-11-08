package java.time.temporal

enum class ChronoUnit(internal val millis: Long) {
    MILLIS(1), SECONDS(1000), MINUTES(60_000), HOURS(3_600_000), HALF_DAYS(43_200_000), DAYS(86_400_000), WEEKS(604_800_000);
    fun between(start: java.time.Instant, end: java.time.Instant): Long = start.until(end, this)
}
