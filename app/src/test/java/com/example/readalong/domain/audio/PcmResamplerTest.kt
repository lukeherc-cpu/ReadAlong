package com.example.readalong.domain.audio

import org.junit.Assert.*
import org.junit.Test

class PcmResamplerTest {
    @Test fun outputCountAndPhaseStayStableAcrossDecoderBuffers() {
        val mono = mutableListOf<Float>()
        val resampler = PcmResampler(44100) { mono += it }
        repeat(441) { resampler.sample(0.25f) }
        repeat(441) { resampler.sample(0.25f) }
        assertEquals(320, mono.size)
        assertTrue(mono.all { kotlin.math.abs(it - 0.25f) < 0.00001 })
    }
    @Test fun sourceRateSamplesAreUnchangedAtSixteenKilohertz() {
        val mono = mutableListOf<Float>()
        val resampler = PcmResampler(16000) { mono += it }
        listOf(-1f, 0f, 1f).forEach(resampler::sample)
        assertEquals(listOf(-1f, 0f, 1f), mono)
    }
}
