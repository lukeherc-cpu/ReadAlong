package com.example.readalong.domain.model

import kotlin.math.roundToInt
import kotlin.math.sqrt

const val MIN_SPEED = .5f
const val MAX_SPEED = 3f
/** Quick picks in the speed sheet; the slider covers everything between in 0.05 steps. */
val SpeedChips = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

/** Snaps to 0.05 between [MIN_SPEED] and [MAX_SPEED], so 1.4 stays 1.4 rather than 1.4000001. */
fun roundSpeed(value: Float): Float = ((value.coerceIn(MIN_SPEED, MAX_SPEED) * 20).roundToInt() / 20f)

/** "1×", "1.25×", "1.4×". */
fun speedLabel(value: Float): String {
    val rounded = roundSpeed(value)
    val text = if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, rounded).trimEnd('0')
    return "$text×"
}

/**
 * How far to go back when play resumes after a pause of [pausedMs]. SMART grows with the time away:
 * 2 s after a short pause, 10 s after 5 minutes, 30 s after an hour. SENTENCE goes back to the start of
 * the sentence being read ([sentenceBehindMs] behind), or acts like SMART when there's no transcript.
 */
fun resumeRewindMs(mode: String, pausedMs: Long, sentenceBehindMs: Long? = null): Long = when (mode) {
    "OFF" -> 0
    "SHORT" -> 3_000
    "LONG" -> 10_000
    "SENTENCE" -> sentenceBehindMs?.takeIf { it in 0..30_000 } ?: resumeRewindMs("SMART", pausedMs)
    else -> when {
        pausedMs < 1_000 -> 0
        pausedMs < 5 * 60_000 -> 2_000
        pausedMs < 60 * 60_000 -> 10_000
        else -> 30_000
    }
}

const val SLEEP_FADE_MS = 20_000L

/** Player volume as the sleep timer runs out: full until the last 20 seconds, then down to silence. */
fun sleepFadeVolume(remainingMs: Long?): Float = when {
    remainingMs == null || remainingMs >= SLEEP_FADE_MS -> 1f
    remainingMs <= 0 -> 0f
    else -> remainingMs.toFloat() / SLEEP_FADE_MS
}

/** A deliberate shake: two strong jolts (over 2.5 g) within 600 ms, at most once every 2 seconds. */
class ShakeDetector(private val threshold: Float = 2.5f) {
    private var lastJolt = Long.MIN_VALUE / 2
    private var lastShake = Long.MIN_VALUE / 2
    fun onSample(x: Float, y: Float, z: Float, timeMs: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z) / 9.81f
        if (g < threshold) return false
        val shake = timeMs - lastJolt in 80..600 && timeMs - lastShake > 2_000
        lastJolt = timeMs
        if (shake) lastShake = timeMs
        return shake
    }
}
