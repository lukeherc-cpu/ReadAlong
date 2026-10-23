package com.example.readalong.domain

import com.example.readalong.domain.transcription.*
import org.junit.Assert.*
import org.junit.Test

class PreparationProgressTest {
    private fun input(chapter: String, start: Long, end: Long) = TranscriptionInput("b", chapter, "audio", start, end, false)
    @Test fun percentagesUseAudioDurationAndOnlyTheCurrentSectionProgress() {
        val inputs = listOf(input("a", 0, 30_000), input("a", 30_000, 40_000), input("b", 40_000, 70_000))
        val progress = preparationProgress(inputs, 1, 50)
        assertEquals(1, progress.chapter); assertEquals(87, progress.chapterPercent); assertEquals(50, progress.bookPercent)
        val next = preparationProgress(inputs, 2, 50)
        assertEquals(2, next.chapter); assertEquals(50, next.chapterPercent); assertEquals(78, next.bookPercent)
        assertEquals(100, preparationProgress(inputs, 3).bookPercent)
        assertEquals(100, preparationProgress(inputs, 3).chapterPercent)
    }
    @Test fun pausedJobsAndUnknownPlansDoNotInventProgress() {
        assertEquals(0, preparationProgress(emptyList(), 10).bookPercent)
        val inputs = listOf(input("a", 0, 30_000), input("b", 30_000, 40_000))
        assertEquals(0, preparationProgress(inputs, 1, chapterId = "b").chapterPercent)
        assertEquals(75, preparationProgress(inputs, 1).bookPercent)
        assertEquals(100, preparationProgress(inputs, 2, 900).bookPercent)
    }
}
