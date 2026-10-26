package com.example.readalong.data.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FriendlyMessagesTest {
    @Test fun estimateBeforeStartingReadsAsWholeHoursOrMinutes() {
        assertEquals("1 minute", TranscriptionEstimate.duration(20_000))
        assertEquals("45 minutes", TranscriptionEstimate.duration(45 * 60_000L))
        assertEquals("1 hour", TranscriptionEstimate.duration(70 * 60_000L))
        // A 30 h book at a fifth of real time: about 6 hours.
        assertEquals("6 hours", TranscriptionEstimate.duration((30 * 3_600_000L * .2).toLong()))
    }

    @Test fun failureExplanationsNeverShowClassNames() {
        val missing = java.io.FileNotFoundException("content://x: open failed: ENOENT")
        val text = PreparationRetry.explanation(IllegalStateException("wrapped", missing))
        assertFalse(text, text.contains("Exception"))
        assertEquals("Can't open this book's audio. Its folder may have moved; choose it again in Library folders.", text)
        assertFalse(PreparationRetry.explanation(RuntimeException("boom")).contains("RuntimeException"))
        // The technical form, for diagnostics, still has them.
        assert(PreparationRetry.technical(IllegalStateException("wrapped", missing)).contains("FileNotFoundException"))
    }
}
