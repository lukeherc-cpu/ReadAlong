package com.example.readalong.domain.reading

import com.example.readalong.domain.reading.LiveWriting.Entry
import org.junit.Assert.*
import org.junit.Test

class LiveWritingTest {
    private val first = listOf(Entry(0, "a", "It is a truth universally acknowledged."), Entry(1, "b", "That a single man in possession."))

    private fun primed() = LiveWriting().apply { accept(first, 2, animate = true, nowMs = 0) }

    @Test fun firstLoadIsShownAtOnce() {
        val writing = primed()
        assertFalse(writing.writing)
        assertNull(writing.written("a"))
    }

    @Test fun newParagraphPastTheEndIsWrittenWordByWord() {
        val writing = primed()
        val added = Entry(2, "c", "However little known the feelings or views of such a man may be.")
        assertTrue(writing.accept(first + added, 3, animate = true, nowMs = 20_000))
        assertTrue(writing.writing)
        assertEquals(0f, writing.written("c")!!.position, 0f)
        assertNull(writing.written("a"))
        // Nothing of the new paragraph is visible yet.
        assertEquals(LiveWriting.fadingWords(added.text, 0f).size, added.text.split(' ').size)
        writing.advance(1_000f)
        val position = writing.written("c")!!.position
        assertTrue(position > 0f && position < added.text.length)
        while (writing.writing) writing.advance(100f)
        assertNull(writing.written("c"))
    }

    @Test fun continuedParagraphOnlyWritesTheNewWords() {
        val writing = primed()
        val grown = first.last().copy(text = first.last().text + " Must be in want of a wife.")
        writing.accept(listOf(first[0], grown), 2, animate = true, nowMs = 20_000)
        val written = writing.written("b")!!
        assertEquals(first.last().text.length, written.from)
        assertEquals(first.last().text.length.toFloat(), written.position, 0f)
        // Old words stay inked, even the ones just before the cursor.
        assertTrue(LiveWriting.fadingWords(grown.text, written.position, written.from).all { it.first > first.last().text.length })
    }

    @Test fun pagesLoadedWhileScrollingAreNotWritten() {
        val writing = LiveWriting()
        writing.accept(first, 10, animate = true, nowMs = 0)
        assertFalse(writing.accept(first + Entry(5, "f", "Loaded later."), 10, animate = true, nowMs = 1_000))
        assertFalse(writing.writing)
    }

    @Test fun notWatchingShowsEverything() {
        val writing = primed()
        writing.accept(first + Entry(2, "c", "New words."), 3, animate = true, nowMs = 10_000)
        assertTrue(writing.writing)
        writing.accept(first + Entry(2, "c", "New words.") + Entry(3, "d", "More."), 4, animate = false, nowMs = 20_000)
        assertFalse(writing.writing)
        assertNull(writing.written("d"))
    }

    @Test fun rewrittenParagraphStopsWriting() {
        val writing = primed()
        writing.accept(first + Entry(2, "c", "New words here."), 3, animate = true, nowMs = 10_000)
        writing.accept(first + Entry(2, "c", "Corrected text."), 3, animate = true, nowMs = 11_000)
        assertFalse(writing.writing)
    }

    @Test fun largeBacklogIsShownAtOnce() {
        val writing = primed()
        writing.accept(first + Entry(2, "c", "word ".repeat(2_000)), 3, animate = true, nowMs = 10_000)
        assertFalse(writing.writing)
    }

    @Test fun paceFinishesBeforeTheNextChunk() {
        val writing = primed()
        val text = "word ".repeat(80).trim()
        writing.accept(first + Entry(2, "c", text), 3, animate = true, nowMs = 20_000)
        var elapsed = 0f
        while (writing.writing) { writing.advance(16f); elapsed += 16f }
        assertTrue("took $elapsed ms", elapsed <= writing.chunkIntervalMs)
        // And doesn't dump it: at least a few seconds of writing for ~400 characters.
        assertTrue("took $elapsed ms", elapsed >= 3_000f)
    }

    @Test fun fadingWordsRampOpacity() {
        val text = "one two three"
        val words = LiveWriting.fadingWords(text, 4f + LiveWriting.FadeChars / 2)
        assertEquals(listOf(0, 4, 8), words.map { it.first })
        assertEquals(.5f, words[1].third, .01f)
        assertTrue(words[0].third > words[1].third && words[1].third > words[2].third)
        // Words the cursor hasn't reached are transparent; long-written ones aren't listed at all.
        assertEquals(0f, LiveWriting.fadingWords(text, 4f).last().third, 0f)
        assertTrue(LiveWriting.fadingWords(text, 40f).isEmpty())
    }
}
