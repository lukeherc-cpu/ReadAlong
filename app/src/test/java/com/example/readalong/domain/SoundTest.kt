package com.example.readalong.domain

import com.example.readalong.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SoundTest {
    private val rate = 44_100

    @Test
    fun filtersHitTheirTargets() {
        assertEquals(6.0, Biquad.peaking(rate, 910.0, 6.0).responseDb(rate, 910.0), 0.05)
        assertEquals(-6.0, Biquad.shelf(rate, 60.0, -6.0, high = false).responseDb(rate, 15.0), 0.3)
        assertEquals(0.0, Biquad.shelf(rate, 60.0, -6.0, high = false).responseDb(rate, 2000.0), 0.1)
        assertEquals(5.0, Biquad.shelf(rate, 14000.0, 5.0, high = true).responseDb(rate, 20000.0), 0.5)
        assertTrue(Biquad.highPass(rate, 80.0).responseDb(rate, 20.0) < -20)
        assertEquals(0.0, Biquad.highPass(rate, 80.0).responseDb(rate, 1000.0), 0.1)
    }

    @Test
    fun settingsSurviveSavingAndRejectJunk() {
        val value = SoundSettings(equalizer = true, preset = EqPreset.CUSTOM, customGains = listOf(1, -2, 3, -4, 5), volumeDb = 6, pitch = -1, skipSilence = true, noiseReduction = true)
        assertEquals(value, SoundSettings.decode(value.encode()))
        assertNull(SoundSettings.decode("nonsense"))
        assertNull(SoundSettings.decode("1;NOPE;0,0,0,0,0;0;0;0;0"))
        assertEquals(12, SoundSettings.decode("1;FLAT;0,0,0,0,0;40;0;0;0")!!.volumeDb)
    }

    @Test
    fun movingABandMakesTheCurveCustomAndKeepsThePreset() {
        val voice = SoundSettings(equalizer = true, preset = EqPreset.VOICE)
        val moved = voice.withBand(0, 20)
        assertEquals(EqPreset.CUSTOM, moved.preset)
        assertEquals(listOf(12) + EqPreset.VOICE.gains.drop(1), moved.gains)
        assertEquals(EqPreset.VOICE.gains, moved.copy(preset = EqPreset.VOICE).gains)
    }

    @Test
    fun neutralSettingsLeaveAudioUntouched() {
        val chain = SoundChain(rate, 2)
        chain.configure(SoundSettings(equalizer = false, pitch = 3, skipSilence = true))
        val samples = FloatArray(64) { sin(it / 3.0).toFloat() * .5f }
        val before = samples.copyOf()
        chain.process(samples)
        assertArrayEquals(before, samples, 0f)
    }

    @Test
    fun volumeBoostsAndNeverClips() {
        val chain = SoundChain(rate, 1)
        chain.configure(SoundSettings(volumeDb = 6))
        val quiet = FloatArray(4410) { (0.2 * sin(2 * PI * 440 * it / rate)).toFloat() }
        chain.process(quiet)
        assertEquals(0.4, quiet.maxOf { abs(it) }.toDouble(), 0.01)
        chain.configure(SoundSettings(volumeDb = 12))
        val loud = FloatArray(4410) { (0.9 * sin(2 * PI * 440 * it / rate)).toFloat() }
        chain.process(loud)
        assertTrue(loud.all { abs(it) <= 1f })
    }

    @Test
    fun pitchIsInSemitones() {
        assertEquals(1f, SoundSettings().pitchFactor, 0f)
        assertEquals(2f, SoundSettings(pitch = 12).pitchFactor, 1e-4f)
    }
}
