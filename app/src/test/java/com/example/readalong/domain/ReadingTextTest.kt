package com.example.readalong.domain

import com.example.readalong.domain.model.*
import com.example.readalong.domain.reading.*
import com.example.readalong.domain.transcription.SpeechModels
import org.junit.Assert.*
import org.junit.Test

class ReadingTextTest {
    private fun passage(index: Int, text: String) = SearchPassage(index, TranscriptSegment("s$index", "b", "c", index * 1000L, index * 1000L + 1000, text))
    @Test fun sentenceSearchIgnoresCasePunctuationAndLineBreaksAndRetainsCharacterOffset() {
        val scan = PassageSearch("THE gate opened, quietly")
        val hit = scan.feed(passage(4, "Earlier words. The gate\nopened—quietly. Later words.")).single()
        assertEquals(4, hit.passage.index); assertEquals(15, hit.offset)
        assertEquals("The gate\nopened—quietly", hit.passage.segment.text.substring(hit.offset, hit.end))
    }
    @Test fun sentenceCanSpanParagraphsAndPagesWithoutDuplicateHits() {
        val scan = PassageSearch("opened quietly at sunrise")
        assertTrue(scan.feed(passage(0, "The gate opened.")).isEmpty())
        val hit = scan.feed(passage(1, "Quietly at sunrise the traveler left.")).single()
        assertEquals(0, hit.passage.index); assertEquals(9, hit.offset)
        assertTrue(scan.feed(passage(2, "A different sentence.")).isEmpty())
    }
    @Test fun repetitionsReturnSeparateLocationsAndPartialWordsDoNotMatch() {
        val scan = PassageSearch("river")
        assertEquals(2, scan.feed(passage(0, "River and rivers and river.")).size)
        assertEquals(1, scan.feed(passage(1, "The river.")).size)
    }
    @Test fun longParagraphPreviewFollowsTheActualWordInsteadOfItsOpeningLines() {
        val text = "earlier ".repeat(80) + "lantern gate"
        val tokens = text.trim().split(' ')
        val timed = tokens.mapIndexed { i, word -> TranscriptWord(word, i * 100L, i * 100L + 100) }
        val segment = TranscriptSegment("s", "b", "c", 0, 9000, text, words = timed)
        val preview = followPreview(segment, 8050)
        assertTrue(preview.text.startsWith("… ")); assertTrue(preview.text.contains("lantern"))
        assertEquals("lantern", preview.text.substring(preview.highlightStart, preview.highlightEnd))
    }
    @Test fun importedPreviewMovesToItsActiveAnchorAndUsesWordTimesOnlyForExactSequences() {
        val text = "Introduction ".repeat(80) + "The lantern shone."
        val start = text.indexOf("The lantern")
        val source = TranscriptSegment("raw", "b", "c", 100, 400, "The lantern shone.", words = listOf(
            TranscriptWord("The", 100, 200), TranscriptWord("lantern", 200, 300), TranscriptWord("shone.", 300, 400)))
        val imported = TranscriptSegment("e", "b", "c", 100, 400, text, anchors = listOf(TranscriptAnchor("raw", 100, 400, start, text.length)), imported = true)
        val preview = followPreview(imported, 250, source)
        assertEquals("lantern", preview.text.substring(preview.highlightStart, preview.highlightEnd))
        val uncertain = followPreview(imported, 250, source.copy(words = source.words.map { it.copy(word = "other") }))
        assertTrue(uncertain.text.substring(uncertain.highlightStart, uncertain.highlightEnd).startsWith("The lantern"))
    }
    @Test fun generatedParagraphUsesCanonicalWordTimesAndDoesNotHighlightMusicOrWordGaps() {
        val canonical = TranscriptSegment("raw", "b", "c", 0, 30_000, "The lantern glowed.", words = listOf(
            TranscriptWord("The", 9000, 9400), TranscriptWord("lantern", 10_000, 10_700), TranscriptWord("glowed.", 11_000, 11_700)))
        val prefix = "Opening credits. "
        val paragraph = canonical.copy(id = "generated", text = prefix + canonical.text, words = emptyList(),
            anchors = listOf(TranscriptAnchor("raw", 0, 30_000, prefix.length, prefix.length + canonical.text.length)))
        val range = timedTextRange(paragraph, 10_200, canonical)
        assertEquals("lantern", paragraph.text.substring(range.first, range.second))
        for (time in listOf(5000L, 9700L, 12_000L)) {
            val gap = timedTextRange(paragraph, time, canonical)
            assertEquals("No word should light up during music/pauses", gap.first, gap.second)
        }
    }
    @Test fun musicGapIsNotHighlightedWhenEpubCorrectsARecognizedSpelling() {
        val source = TranscriptSegment("raw", "b", "c", 0, 10_000, "The guerilla rested.", words = listOf(
            TranscriptWord("The", 500, 800), TranscriptWord("guerilla", 900, 1400), TranscriptWord("rested.", 5000, 5500)))
        val corrected = source.copy(text = "The gorilla rested.", words = emptyList(), imported = true,
            anchors = listOf(TranscriptAnchor("raw", 0, 10_000, 0, 19)))
        val gap = timedTextRange(corrected, 3000, source)
        assertEquals(gap.first, gap.second)
    }
    @Test fun modelCatalogOnlyOffersPinnedWhistle() {
        assertEquals(listOf(SpeechModels.whistle), SpeechModels.all)
        assertEquals(64, SpeechModels.whistle.sha256.length)
        assertTrue(SpeechModels.whistle.url.startsWith("https://"))
        assertFalse(SpeechModels.whistle.url.contains("/main/"))
        assertNull(SpeechModels.find("whisper-base.en-v1"))
        assertNull(SpeechModels.find("sherpa-zipformer-en-20m-int8-v1"))
        assertNull(SpeechModels.find("unknown"))
    }
}
