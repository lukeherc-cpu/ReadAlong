package com.example.readalong.domain.reading

import org.junit.Assert.*
import org.junit.Test

class SyncTimeTest {
    @Test fun exactTimesRoundTripIncludingLongBooksAndSubseconds() {
        listOf(0L, 1L, 59_999L, 3_600_001L, 100 * 3_600_000L + 123L).forEach {
            assertEquals(it, parseSyncTime(syncTime(it)))
        }
        assertEquals(62_300L, parseSyncTime("1:02.3"))
        assertEquals(3_723_040L, parseSyncTime("1:02:03.04"))
    }
    @Test fun invalidOrAmbiguousInputsCannotCreateAnchors() {
        listOf("", "-1:00", "0:60", "1:60:00", "1:2", "NaN", "12", "1:00.1234", "999999999999:00:00").forEach {
            assertNull(it, parseSyncTime(it))
        }
    }
}
