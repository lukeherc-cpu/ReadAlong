package com.example.readalong.domain

import com.example.readalong.domain.transcription.TranscriptionBatch
import org.junit.Assert.*
import org.junit.Test

class TranscriptionBatchTest {
    @Test fun shortUnitsShareOneBoundedSession() {
        val budget = TranscriptionBatch()
        assertTrue(budget.continueAfter(10_000, 10_000, false))
        assertTrue(budget.continueAfter(10_000, 20_000, false))
        assertTrue(budget.continueAfter(10_000, 30_000, false))
        repeat(28) { assertTrue(budget.continueAfter(1_000, 31_000L + it * 1_000, false)) }
        assertFalse(budget.continueAfter(1_000, 60_000, false))
    }
    @Test fun slowUnitsOrResourcePressureYieldAtTheCheckpoint() {
        assertFalse(TranscriptionBatch().continueAfter(160_000, 160_000, false))
        assertFalse(TranscriptionBatch().continueAfter(1_000, 1_000, true))
        val budget = TranscriptionBatch()
        assertTrue(budget.continueAfter(35_000, 35_000, false))
        // A faster last unit must not erase the earlier, slower estimate.
        assertFalse(budget.continueAfter(10_000, 310_000, false))
    }
}
