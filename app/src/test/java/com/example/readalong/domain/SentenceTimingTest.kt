package com.example.readalong.domain

import com.example.readalong.domain.model.*
import com.example.readalong.domain.reading.*
import org.junit.Assert.*
import org.junit.Test

class SentenceTimingTest {
    private val spoken = TranscriptSegment("raw", "b", "c", 0, 30_000, "The guerilla rested. Then he left.", words = listOf(
        TranscriptWord("The", 5_000, 5_200), TranscriptWord("guerilla", 5_300, 5_800), TranscriptWord("rested.", 7_000, 7_800),
        TranscriptWord("Then", 13_000, 13_300), TranscriptWord("he", 13_400, 13_600), TranscriptWord("left.", 14_000, 14_500)))

    @Test fun spokenHeadingDoesNotDiscardOrMoveTheFollowingVerbatimSentence() {
        val sentence = "The two men appeared out of nowhere, a few yards apart in the narrow moonlit lane."
        val prefix = "CHAPTER I THE DARK LORD ASCENDING "
        val text = prefix + sentence
        val words = text.split(' ').mapIndexed { i,w -> TranscriptWord(w, 1000L+i*300,1250L+i*300) }
        val raw = spoken.copy(text=text,words=words,startMs=1000,endMs=words.last().endMs)
        val blocks = listOf(com.example.readalong.domain.epub.EpubBlock(0,0,"body",sentence))
        val result = com.example.readalong.domain.alignment.EpubAligner().align(blocks,listOf(raw))
        val p=raw.copy(id="epub",text=sentence,words=emptyList(),imported=true,anchors=result.blocks.single().anchors)
        val cue=sentenceCues(p,mapOf(raw.id to raw)).single()
        assertEquals(words[6].startMs,cue.audioStartMs)
        assertEquals(prefix.length,p.anchors.single().sourceStart)
    }

    @Test fun middleOfAChunkSplitSentenceIsNotAdvertisedAsItsBeginning() {
        val source = spoken.copy(text = "quietly rested.", startMs = 7_000,
            words = listOf(TranscriptWord("quietly", 7_000, 7_300), TranscriptWord("rested.", 7_400, 7_800)))
        val text = "The gorilla quietly rested."
        val from = text.indexOf("quietly")
        val p = source.copy(id = "epub", text = text, imported = true, words = emptyList(),
            anchors = listOf(TranscriptAnchor(source.id, 7_000, 7_800, from, text.length, 0, source.text.length)))
        assertNull(sentenceCues(p, mapOf(source.id to source)).single().audioStartMs)
    }

    @Test fun misrecognizedNameAtTheOpeningKeepsTheRecognizedSentenceBeginning() {
        val text = "Lord Tresting stood with his guest on a small hilltop patio overlooking the fields."
        val rawText = "Trusting stood with his guest on a small hilltop patio overlooking the fields."
        val words = rawText.split(' ').mapIndexed { i, w -> TranscriptWord(w, 4000L + i * 400, 4300L + i * 400) }
        val raw = spoken.copy(text = rawText, startMs = 4000, endMs = words.last().endMs, words = words)
        val aligned = com.example.readalong.domain.alignment.EpubAligner().align(
            listOf(com.example.readalong.domain.epub.EpubBlock(0, 0, "p", text)), listOf(raw))
        val p = raw.copy(id = "epub", text = text, imported = true, words = emptyList(), anchors = aligned.blocks.single().anchors)
        assertEquals(4000L, sentenceCues(p, mapOf(raw.id to raw)).single().audioStartMs)
    }

    @Test fun seekingIncludesTheAttackButNeverThePreviousSpokenWord() {
        val cue = SentenceCue(0, 12, 5000, 6000)
        val current = spoken.copy(words = listOf(TranscriptWord("start", 5000, 5500)))
        assertEquals(4840L, sentenceSeekStart(cue, listOf(current)))
        assertEquals(5000L, sentenceSeekStart(cue, emptyList()))
        val previous = spoken.copy(words = listOf(TranscriptWord("before.", 4700, 4950)))
        assertEquals(4950L, sentenceSeekStart(cue, listOf(previous, current)))
        assertEquals(0L, sentenceSeekStart(cue.copy(audioStartMs = 80), listOf(current.copy(words = listOf(TranscriptWord("start", 80, 300))))))
    }

    @Test fun latestActiveSentenceWinsOverlappingTimesAndMusicHasNoActiveSentence() {
        val cues = listOf(SentenceCue(0, 10, 1000, 6000), SentenceCue(11, 20, 5000, 7000))
        assertEquals(1, activeSentenceIndex(cues, 5500))
        assertEquals(-1, activeSentenceIndex(cues, 9000))
        assertEquals(-1, activeSentenceIndex(cues, 0))
    }

    @Test fun punctuationQuotesAbbreviationsAndDecimalsRetainSentenceBoundaries() {
        val text = "Dr. Lee paid 3.50. “Is it ready?” Yes!\nAnother line."
        assertEquals(listOf("Dr. Lee paid 3.50.", "“Is it ready?”", "Yes!", "Another line."), sentenceRanges(text).map { text.substring(it) })
    }
    @Test fun tapInSecondSentenceUsesItsFirstWordNotTheContainingWhisperBlock() {
        val cues = sentenceCues(spoken)
        assertEquals(13_000L, sentenceAt(cues, spoken.text.indexOf("left"))?.audioStartMs)
        assertEquals(5_000L, cues.first().audioStartMs)
        assertEquals(7_800L, cues.first().audioEndMs)
    }
    @Test fun sentenceHighlightSurvivesInternalWordGapsButNotIntroOrBetweenSentenceMusic() {
        val cues = sentenceCues(spoken)
        assertEquals("The guerilla rested.", spoken.text.substring(cues.first().start, cues.first().end))
        assertTrue(cues.first().activeAt(6_500))
        assertFalse(cues.any { it.activeAt(2_000) })
        assertFalse(cues.any { it.activeAt(10_000) })
    }
    @Test fun epubSpellingAndMissingRecognitionDoNotMakeTheSentenceFlash() {
        val text = "The gorilla quietly rested. Then he left."
        val imported = spoken.copy(id = "epub", text = text, words = emptyList(), imported = true,
            anchors = listOf(TranscriptAnchor(spoken.id, 0, 30_000, 0, text.length)))
        val cues = sentenceCues(imported, mapOf(spoken.id to spoken))
        assertEquals(5_000L, cues.first().audioStartMs)
        assertTrue(cues.first().activeAt(6_500))
        assertEquals(13_000L, cues[1].audioStartMs)
    }
    @Test fun oneWhisperSegmentAcrossSeveralEpubParagraphsKeepsEachSentenceStart() {
        val sentences = listOf("The traveler opened the garden gate.", "A silver lantern shone beside the bridge.",
            "Three small boats crossed the quiet river.", "The keeper closed the shutters at dawn.")
        val text = sentences.joinToString(" ")
        val words = text.split(' ').mapIndexed { i, word -> TranscriptWord(word, 1000L + i * 700, 1500L + i * 700) }
        val raw = TranscriptSegment("multi", "b", "c", 1000, 25_000, text, words = words)
        val blocks = sentences.mapIndexed { i, sentence -> com.example.readalong.domain.epub.EpubBlock(i, 0, "p$i", sentence) }
        val result = com.example.readalong.domain.alignment.EpubAligner().align(blocks, listOf(raw))
        var word = 0
        for (block in result.blocks) {
            val p = raw.copy(id = "epub:${block.block.ordinal}", text = block.block.text, words = emptyList(), anchors = block.anchors, imported = true)
            assertEquals(words[word].startMs, sentenceCues(p, mapOf(raw.id to raw)).single().audioStartMs)
            word += block.block.text.split(' ').size
        }
    }
    @Test fun repeatedSentenceWithinOneSegmentUsesItsOwnSourceRange() {
        val line = "Please close the garden gate before you leave."
        val text = "The morning visitor arrived with a basket. $line The evening visitor returned with a suitcase. $line"
        val words = text.split(' ').mapIndexed { i, w -> TranscriptWord(w, i * 500L, i * 500L + 400) }
        val raw = TranscriptSegment("repeated", "b", "c", 0, 30_000, text, words = words)
        val from = text.lastIndexOf(line)
        val p = raw.copy(id = "epub", text = line, imported = true, words = emptyList(),
            anchors = listOf(TranscriptAnchor(raw.id, 0, 30_000, 0, line.length, from, text.length)))
        val expected = words[text.substring(0, from).trim().split(' ').size].startMs
        assertEquals(expected, sentenceCues(p, mapOf(raw.id to raw)).single().audioStartMs)
    }
    @Test fun fittedPhrasesRetainEveryWordWithoutTrailingEllipsis() {
        val text = "A silver lantern shone beside the quiet river at dawn."
        val ranges = fittingPhrases(text) { it.length <= 18 }
        assertTrue(ranges.size > 1)
        assertTrue(ranges.all { text.substring(it).length <= 18 })
        assertEquals(text, ranges.joinToString(" ") { text.substring(it) })
    }
    @Test fun unmatchedEpubSentencesHaveNoInventedAudioPosition() {
        val text = "Unmatched opening. The gorilla rested. Unmatched ending."
        val from = text.indexOf("The gorilla")
        val imported = spoken.copy(id = "epub", text = text, imported = true, words = emptyList(),
            anchors = listOf(TranscriptAnchor("raw", 5_000, 7_800, from, from + "The gorilla rested.".length)))
        val source = spoken.copy(text = "The guerilla rested.", words = spoken.words.take(3))
        val cues = sentenceCues(imported, mapOf("raw" to source))
        assertNull(cues[0].audioStartMs); assertEquals(5_000L, cues[1].audioStartMs); assertNull(cues[2].audioStartMs)
    }
}
