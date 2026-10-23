package com.example.readalong.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeTest {
    @Test fun chapterPositionUsesAbsoluteMilliseconds() {
        assertEquals(3_600_000L, chapterStart(3_612_000))
        assertEquals("0:12", clockTime(3_612_000 - chapterStart(3_612_000)))
        assertEquals("30:00", clockTime(1_800_000))
        assertEquals("30:00:00", clockTime(108_000_000))
    }
}
