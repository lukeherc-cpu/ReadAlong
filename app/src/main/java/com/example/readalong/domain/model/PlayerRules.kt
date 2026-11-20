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

/**
 * A deliberate shake, at any strength a sleepy hand manages: gravity is filtered out, and three
 * swings of the phone (over about 1.1 g each) within 1.2 seconds count as one shake, at most once
 * every 1.5 seconds. Walking, turning over in bed or setting the phone down stay well under that.
 *
 * Each swing is counted once, however many samples it spans: the next one only counts after the
 * movement has eased off again, so the sensor's sampling rate doesn't change what a shake is.
 */
class ShakeDetector(
    private val threshold: Float = 11f,
    private val rearm: Float = 6f,
    private val swingsNeeded: Int = 3,
    private val windowMs: Long = 1_200,
    private val cooldownMs: Long = 1_500,
) {
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var lastSample = Long.MIN_VALUE
    private var armed = true
    private val swings = ArrayDeque<Long>()
    private var lastShake = Long.MIN_VALUE / 2

    /** Takes one accelerometer sample in m/s² (gravity included) at [timeMs]; true when it completes a shake. */
    fun onSample(x: Float, y: Float, z: Float, timeMs: Long): Boolean {
        if (lastSample == Long.MIN_VALUE || timeMs - lastSample > 1_000) {
            // First sample, or the sensor paused: start the gravity estimate from here.
            gravityX = x; gravityY = y; gravityZ = z; lastSample = timeMs; armed = true; swings.clear()
            return false
        }
        val dt = (timeMs - lastSample).coerceAtLeast(1).toFloat()
        lastSample = timeMs
        val alpha = dt / (GRAVITY_SMOOTHING_MS + dt)
        gravityX += alpha * (x - gravityX); gravityY += alpha * (y - gravityY); gravityZ += alpha * (z - gravityZ)
        val lx = x - gravityX; val ly = y - gravityY; val lz = z - gravityZ
        val force = sqrt(lx * lx + ly * ly + lz * lz)
        if (!armed) {
            if (force < rearm) armed = true
            return false
        }
        if (force < threshold) return false
        armed = false
        swings.addLast(timeMs)
        while (swings.isNotEmpty() && timeMs - swings.first() > windowMs) swings.removeFirst()
        if (swings.size < swingsNeeded || timeMs - lastShake < cooldownMs) return false
        swings.clear()
        lastShake = timeMs
        return true
    }

    private companion object { const val GRAVITY_SMOOTHING_MS = 400f }
}
