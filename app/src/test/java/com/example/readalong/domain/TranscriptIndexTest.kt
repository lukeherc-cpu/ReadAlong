package com.example.readalong.domain

import com.example.readalong.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TranscriptIndexTest {
    private val first = TranscriptSegment("one", "book", "chapter", 100, 1_000, "First passage")
    private val second = TranscriptSegment("two", "book", "chapter", 1_000, 2_000, "Second passage")
    private val last = TranscriptSegment("last", "book", "chapter", 3_000, 4_000, "After a gap")
    @Test fun timestampBoundariesGapsAndReverseMapping() {
        val index = TranscriptIndex(listOf(first, second, last))
        assertNull(index.at(-1)); assertNull(index.at(0))
        assertEquals(first, index.at(100)); assertEquals(first, index.at(999))
        assertEquals(second, index.at(1_000)); assertNull(index.at(2_000)); assertNull(index.at(4_000))
        assertEquals(3_000L, index.audioPosition("last")); assertNull(index.audioPosition("missing"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsAmbiguousOverlappingSegments() {
        TranscriptIndex(listOf(first, second.copy(startMs = 900)))
    }
    @Test fun handlesThirtyHourBookWithoutPercentageMapping() {
        val segment = last.copy(startMs = 108_000_000, endMs = 108_001_000)
        assertEquals(segment, TranscriptIndex(listOf(segment)).at(108_000_123))
    }
}
