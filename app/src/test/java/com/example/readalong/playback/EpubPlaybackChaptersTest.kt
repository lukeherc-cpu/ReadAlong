package com.example.readalong.playback

import com.example.readalong.data.epub.TimedEpubChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubPlaybackChaptersTest {
    private val contents = listOf(
        TimedEpubChapter(0, "Title Page", -1), TimedEpubChapter(3, "Dedication", -1),
        TimedEpubChapter(5, "Day 1,299 of My Captivity", 40_000), TimedEpubChapter(9, "The Silver-Dollar Scar", 600_000),
        TimedEpubChapter(20, "Day 1,300 of My Captivity", 1_500_000))

    @Test fun matchedEpubChaptersBecomePlayerChapters() {
        val chapters = epubPlaybackChapters(contents, listOf(2_000_000), "Remarkably Bright Creatures")
        assertEquals(listOf("Remarkably Bright Creatures", "Day 1,299 of My Captivity", "The Silver-Dollar Scar", "Day 1,300 of My Captivity"), chapters.map { it.title })
        assertEquals(listOf(0L, 40_000L, 600_000L, 1_500_000L), chapters.map { it.startMs })
        assertEquals(listOf(40_000L, 560_000L, 900_000L, 500_000L), chapters.map { it.durationMs })
        assertEquals(listOf(0, 1, 2, 3), chapters.map { it.index })
    }

    @Test fun shortLeadInFoldsIntoFirstChapter() {
        val chapters = epubPlaybackChapters(listOf(TimedEpubChapter(0, "One", 2_000), TimedEpubChapter(4, "Two", 50_000)), listOf(100_000), "Book")
        assertEquals(listOf("One", "Two"), chapters.map { it.title })
        assertEquals(0L, chapters.first().startMs)
    }

    @Test fun chaptersMapOntoTheirAudioFile() {
        val chapters = epubPlaybackChapters(contents, listOf(1_000_000, 1_000_000), "Book")
        val last = chapters.last()
        assertEquals(1, last.fileIndex)
        assertEquals(500_000L, last.fileStartMs)
        assertEquals(0, chapters[2].fileIndex)
        assertEquals(600_000L, chapters[2].fileStartMs)
    }

    @Test fun outOfOrderOrUnmatchedStartsAreSkipped() {
        val chapters = epubPlaybackChapters(listOf(TimedEpubChapter(0, "A", 0), TimedEpubChapter(1, "B", 90_000), TimedEpubChapter(2, "C", 30_000), TimedEpubChapter(3, "D", 120_000)), listOf(200_000), "Book")
        assertEquals(listOf("A", "B", "D"), chapters.map { it.title })
        assertTrue(epubPlaybackChapters(listOf(TimedEpubChapter(0, "A", -1)), listOf(200_000), "Book").isEmpty())
    }
}
