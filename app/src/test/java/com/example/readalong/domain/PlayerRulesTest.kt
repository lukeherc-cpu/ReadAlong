package com.example.readalong.domain

import com.example.readalong.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class PlayerRulesTest {
    @Test
    fun speedsSnapAndReadWell() {
        assertEquals(3f, roundSpeed(3.4f))
        assertEquals(.5f, roundSpeed(.1f))
        assertEquals(1.4f, roundSpeed(1.4123f))
        assertEquals("1×", speedLabel(1f))
        assertEquals("1.4×", speedLabel(1.4f))
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("3×", speedLabel(3f))
    }

    @Test
    fun resumeGoesBackFurtherTheLongerYouWereAway() {
        assertEquals(0, resumeRewindMs("SMART", 500))
        assertEquals(2_000, resumeRewindMs("SMART", 30_000))
        assertEquals(10_000, resumeRewindMs("SMART", 6 * 60_000))
        assertEquals(30_000, resumeRewindMs("SMART", 2 * 60 * 60_000))
        assertEquals(0, resumeRewindMs("OFF", 2 * 60 * 60_000))
        assertEquals(3_000, resumeRewindMs("SHORT", 1))
        assertEquals(4_200, resumeRewindMs("SENTENCE", 30_000, 4_200))
        assertEquals(2_000, resumeRewindMs("SENTENCE", 30_000, null))
    }

    @Test
    fun sleepFadesOverTheLastTwentySeconds() {
        assertEquals(1f, sleepFadeVolume(null))
        assertEquals(1f, sleepFadeVolume(60_000))
        assertEquals(.5f, sleepFadeVolume(10_000))
        assertEquals(0f, sleepFadeVolume(-5))
    }

    /** Feeds [seconds] of 50 Hz samples: the phone lying flat plus [amplitude] m/s² swinging along x at [hz]. */
    private fun ShakeDetector.feed(fromMs: Long, seconds: Double, amplitude: Float, hz: Double): List<Long> {
        val shakes = mutableListOf<Long>()
        var t = 0L
        while (t < seconds * 1000) {
            val swing = amplitude * kotlin.math.sin(2 * Math.PI * hz * t / 1000.0).toFloat()
            if (onSample(swing, 0f, 9.81f, fromMs + t)) shakes += fromMs + t
            t += 20
        }
        return shakes
    }

    @Test
    fun anOrdinaryShakeResetsTheTimer() {
        // About 1.5 g back and forth, three times a second: a sleepy but deliberate shake.
        assertEquals(1, ShakeDetector().feed(0, 1.0, 15f, 3.0).size)
        // A harder, faster shake still counts once, not once per sample.
        assertEquals(1, ShakeDetector().feed(0, 0.8, 30f, 5.0).size)
    }

    @Test
    fun keepShakingAndItResetsAgainAfterACooldown() {
        val shakes = ShakeDetector().feed(0, 4.0, 15f, 3.0)
        assertTrue(shakes.size in 2..3)
        shakes.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 1_500) }
    }

    @Test
    fun stillnessWalkingAndASingleBumpAreNotShakes() {
        assertTrue(ShakeDetector().feed(0, 5.0, 0f, 1.0).isEmpty())
        // Walking: about half a g, twice a second.
        assertTrue(ShakeDetector().feed(0, 5.0, 5f, 2.0).isEmpty())
        // One knock on the bedside table.
        val shake = ShakeDetector()
        shake.feed(0, 1.0, 0f, 1.0)
        assertFalse(shake.onSample(40f, 0f, 9.81f, 1_000))
        assertTrue(shake.feed(1_020, 2.0, 0f, 1.0).isEmpty())
    }

    @Test
    fun theTimeOfDayDoesNotMatter() {
        // Sensor timestamps count from boot, so a shake hours in works the same as one at the start.
        assertEquals(1, ShakeDetector().feed(5 * 3_600_000L, 1.0, 15f, 3.0).size)
    }
}
