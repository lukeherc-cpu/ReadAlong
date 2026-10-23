package com.example.readalong.domain

import com.example.readalong.domain.epub.ParagraphFormatter
import com.example.readalong.domain.model.TranscriptSegment
import org.junit.Assert.*
import org.junit.Test

class ParagraphFormatterTest {
    @Test fun groupsAcrossInputBatchesWithoutChangingTextOrLosingAnchors() {
        val source = List(100) { i -> TranscriptSegment("s$i", "book", "chapter", i * 1000L, (i + 1) * 1000L, "Phrase $i with ♪ and “quotes”.") }
        val paragraphs = mutableListOf<List<TranscriptSegment>>()
        val formatter = ParagraphFormatter { paragraphs += it }
        source.chunked(7).forEach { batch -> batch.forEach(formatter::add) }
        formatter.flush()
        assertEquals(source, paragraphs.flatten())
        assertTrue(paragraphs.size < source.size)
        assertTrue(paragraphs.all { it.size <= 12 && it.joinToString(" ") { s -> s.text }.length <= 900 })
    }
    @Test fun chapterChangesAndLongPausesStartNewParagraphs() {
        val output = mutableListOf<List<TranscriptSegment>>()
        val formatter = ParagraphFormatter { output += it }
        formatter.add(TranscriptSegment("a", "b", "one", 0, 1000, "An unfinished phrase"))
        formatter.add(TranscriptSegment("b", "b", "one", 3000, 4000, "A pause."))
        formatter.add(TranscriptSegment("c", "b", "two", 4000, 5000, "A chapter."))
        formatter.flush()
        assertEquals(3, output.size)
    }
}
