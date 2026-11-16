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

    @Test
    fun aShakeNeedsTwoJoltsCloseTogether() {
        val shake = ShakeDetector()
        assertFalse(shake.onSample(0f, 0f, 9.81f, 0))
        assertFalse(shake.onSample(30f, 0f, 0f, 100))
        assertTrue(shake.onSample(30f, 0f, 0f, 300))
        assertFalse(shake.onSample(30f, 0f, 0f, 500))
        assertFalse(shake.onSample(30f, 0f, 0f, 1_200))
        assertFalse(shake.onSample(30f, 0f, 0f, 2_400))
        assertTrue(shake.onSample(30f, 0f, 0f, 2_600))
    }
}
