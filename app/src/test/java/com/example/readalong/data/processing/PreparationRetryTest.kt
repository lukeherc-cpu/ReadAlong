package com.example.readalong.data.processing

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PreparationRetryTest {
    @Test fun retriesThreeTimesWithGrowingDelays() {
        assertEquals(3, PreparationRetry.MAX_RETRIES)
        assertArrayEquals(longArrayOf(5_000, 20_000, 60_000), PreparationRetry.DELAYS_MS)
    }
    @Test fun lostRecognitionProcessIsTemporary() {
        assertTrue(PreparationRetry.transient(IOException("Recognition stopped.")))
        assertTrue(PreparationRetry.transient(IOException("Speech recognition didn't start in time.")))
    }
    @Test fun permanentProblemsAreNotRetried() {
        assertFalse(PreparationRetry.transient(java.io.FileNotFoundException("gone")))
        assertFalse(PreparationRetry.transient(SecurityException("no access")))
    }
}
