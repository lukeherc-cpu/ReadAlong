package com.example.readalong.domain

import com.example.readalong.domain.alignment.EpubAligner
import com.example.readalong.domain.epub.EpubBlock
import com.example.readalong.domain.model.TranscriptSegment
import org.junit.Assert.*
import org.junit.Test

class EpubAlignmentTest {
    private fun block(i: Int, text: String, spine: Int = 0) = EpubBlock(i, spine, "chapter$spine.xhtml#p$i", text)
    private fun segment(i: Int, text: String) = TranscriptSegment("s$i", "book", "audio", i * 10_000L, i * 10_000L + 8_000, text)
    @Test fun openingQuestionJoinedToAnnouncementRecoversWithoutChangingLaterAnchors() {
        val opening = "What is beyond this door?"
        val next = "A distant voice reached me through the thick stone wall."
        val blocks = listOf(block(0, opening), block(1, next), block(2, opening), block(3, "The morning sun filled the old house with golden light."))
        val speech = listOf(segment(0, "Chapter one. $opening"), segment(1, next), segment(2, blocks[3].text))
        val aligned = EpubAligner().align(blocks, speech)
        assertEquals("s0", aligned.blocks[0].anchors.single().id)
        assertEquals("Chapter one. ".length, aligned.blocks[0].anchors.single().sourceStart)
        assertTrue(aligned.blocks[2].anchors.isEmpty())
        assertEquals("s1", aligned.blocks[1].anchors.single().id)
        assertEquals("s2", aligned.blocks[3].anchors.single().id)
    }
    @Test fun repeatedOpeningEpigraphOutsideTheOldPrefixWindowUsesNearbyNarrativeContext() {
        val epigraph = (0 until 20).map { "Remember traveler$it that every distant valley hides another secret road." }
        val unique = "The watchman raised his lantern and signaled to the approaching horseman, who carried an important message from the distant city."
        val blocks = epigraph.mapIndexed { i, t -> block(i, t) } + block(20, unique) +
            block(21, (0 until 1000).joinToString(" ") { "interlude$it" }) +
            epigraph.mapIndexed { i, t -> block(i + 22, t, 1) }
        val speech = epigraph.mapIndexed { i, t -> segment(i, t) } + segment(20, unique)
        val result = EpubAligner().align(blocks, speech)
        assertEquals("s0", result.blocks.first().anchors.single().id)
        assertEquals("s20", result.blocks[20].anchors.single().id)
        assertTrue(result.blocks.drop(21).all { it.anchors.isEmpty() })
    }
    @Test fun openingRecoveryDoesNotAssignAnUnrelatedAnnouncement() {
        val blocks = listOf(block(0, "Where have all the people gone?"), block(1, "A distant voice reached me through the thick stone wall."))
        val result = EpubAligner().align(blocks, listOf(segment(0, "An audiobook narrated by someone else."), segment(1, blocks[1].text)))
        assertTrue(result.blocks[0].anchors.isEmpty())
    }
    @Test fun spellingPunctuationNumbersAndParagraphsUseEpubWordingWithCanonicalTimes() {
        val epub = listOf(block(0, "The gorilla waited quietly beside the river, watching 3 small boats."), block(1, "“Tomorrow,” said the keeper, “we will open the garden gate.”"))
        val raw = listOf(segment(0, "The guerilla waited quietly beside the river watching three small boats"), segment(1, "Tomorrow said the keeper we will open the garden gate"))
        val result = EpubAligner().align(epub, raw)
        assertTrue(result.useful); assertEquals(2, result.matchedSegments)
        assertEquals(epub.map { it.text }, result.blocks.map { it.block.text })
        result.blocks.forEachIndexed { i, b -> assertEquals(raw[i].startMs, b.anchors.single().startMs); assertEquals(raw[i].endMs, b.anchors.single().endMs) }
        assertTrue(raw[0].text.contains("guerilla"))
    }
    @Test fun frontMatterNarratorAnnouncementsAndDifferentChapterBoundariesDoNotShiftMatches() {
        val story = listOf("The lantern shone beside the bridge while quiet footsteps crossed the water.", "A distant bell rang twice before the morning train entered the valley.")
        val blocks = listOf(block(0, (1..100).joinToString(" ") { "Publisher notice number$it." }), block(1, "Chapter One"), block(2, story[0]), block(3, story[1], 1))
        val raw = listOf(segment(0, "An original audio production read by our narrator"), segment(1, story[0]), segment(2, "Chapter two"), segment(3, story[1]))
        val result = EpubAligner().align(blocks, raw)
        assertTrue(result.blocks[0].anchors.isEmpty()); assertTrue(result.blocks[1].anchors.isEmpty())
        assertEquals("s1", result.blocks[2].anchors.single().id)
        assertEquals("s3", result.blocks[3].anchors.single().id)
    }
    @Test fun damagedMiddleSectionDoesNotDestroyLaterMatchesOrInventAudioForMusic() {
        val before = "The caretaker carried a silver lantern across the narrow wooden bridge."
        val after = "At sunrise the captain opened the shutters and watched the quiet harbor."
        val blocks = listOf(block(0, before), block(1, "♪ ♫ ♬"), block(2, "A different edition inserted an unrelated scene about distant mountain travelers."), block(3, after, 1))
        val raw = listOf(segment(0, before), segment(1, "The birds returned to their nests during an unexpected storm."), segment(2, after))
        val result = EpubAligner().align(blocks, raw)
        assertEquals(2, result.matchedSegments); assertTrue(result.blocks[1].anchors.isEmpty()); assertTrue(result.blocks[2].anchors.isEmpty())
        assertEquals("s2", result.blocks[3].anchors.single().id)
    }
    @Test fun entirelyDifferentBookDoesNotReceiveProportionalTimestamps() {
        val result = EpubAligner().align(listOf(block(0, "Engineers checked the spacecraft oxygen supply before approaching the distant planet.")),
            listOf(segment(0, "The gardener planted yellow flowers along the brick wall beside the orchard.")))
        assertFalse(result.useful); assertEquals(0, result.matchedSegments); assertTrue(result.blocks.single().anchors.isEmpty())
    }
    @Test fun repeatedDialogueUsesItsSurroundingUniqueContextInReadingOrder() {
        val line = "Please close the garden gate before you leave."
        val a = "At dawn the first visitor arrived carrying a wicker basket. $line"
        val b = "At night the final visitor returned with an empty suitcase. $line"
        val result = EpubAligner().align(listOf(block(0, a), block(1, b)), listOf(segment(0, a), segment(1, b)))
        assertEquals(listOf("s0", "s1"), result.blocks.map { it.anchors.single().id })
    }
    @Test fun largeMissingSectionReanchorsWithoutAccumulatingDrift() {
        val a = "The young traveler reached a lonely inn before the evening rain began."
        val b = "The old innkeeper served a warm meal beside the stone fireplace."
        val missing = (1..1200).joinToString(" ") { "unrelated$it" }
        val result = EpubAligner().align(listOf(block(0, a), block(1, missing), block(2, b)), listOf(segment(0, a), segment(1, b)))
        assertEquals("s0", result.blocks[0].anchors.single().id); assertTrue(result.blocks[1].anchors.isEmpty()); assertEquals("s1", result.blocks[2].anchors.single().id)
    }
    @Test fun longBookKeepsMonotonicBoundedMappingsAcrossThousandsOfParagraphs() {
        val blocks = (0 until 8000).map { i -> block(i, "Lantern$i shone beside field$i across valley$i while quiet travelers followed the distant road.", i / 200) }
        val raw = blocks.mapIndexed { i, b -> segment(i, b.text) }
        val result = EpubAligner().align(blocks, raw)
        assertEquals(8000, result.matchedSegments)
        result.blocks.forEachIndexed { i, b -> assertEquals("s$i", b.anchors.single().id); assertEquals(i * 10_000L, b.anchors.single().startMs) }
    }
    @Test fun noTranscriptAndMusicalNotationRemainUnmapped() {
        val result = EpubAligner().align(listOf(block(0, "♪ ♫ ♬")), emptyList())
        assertEquals(0f, result.coverage); assertTrue(result.blocks.single().anchors.isEmpty())
    }
    @Test fun repeatedExcerptWithoutDistinctContextDoesNotGuessWhichOccurrenceWasNarrated() {
        val line = "Please close the garden gate before you leave."
        val result = EpubAligner().align(listOf(block(0, line), block(1, line)), listOf(segment(0, line)))
        assertEquals(0, result.matchedSegments)
        assertTrue(result.blocks.all { it.anchors.isEmpty() })
    }
    @Test fun shortSentenceWithPhoneticSpellingKeepsItsOwnLocatorInUniqueContext() {
        val text = "Hello. I am Ivan. I am a gorilla. It's not as easy as it looks."
        val speech = listOf(segment(0, "Hello."), segment(1, "I am Ivan."), segment(2, "I am a guerrilla."), segment(3, "It's not as easy as it looks."))
        val result = EpubAligner().align(listOf(block(0, text)), speech)
        assertEquals(listOf("s0", "s1", "s2", "s3"), result.blocks.single().anchors.map { it.id })
        assertEquals(text, result.blocks.single().block.text)
        assertEquals(speech[2].startMs, result.blocks.single().anchors.first { it.id == "s2" }.startMs)
    }
    @Test fun phoneticSpellingAloneAndUnknownWordDoNotCreateConfidentAudioLinks() {
        assertFalse(EpubAligner().align(listOf(block(0, "gorilla")), listOf(segment(0, "guerrilla"))).useful)
        val text = "Hello. I am Ivan. I am a gorilla. It's not as easy as it looks."
        val speech = listOf(segment(0, "Hello."), segment(1, "I am Ivan."), segment(2, "I am a stranger."), segment(3, "It's not as easy as it looks."))
        val result = EpubAligner().align(listOf(block(0,text)),speech)
        assertTrue(result.blocks.single().anchors.none { it.id == "s2" })
    }
}
