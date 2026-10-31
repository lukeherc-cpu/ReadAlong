package com.example.readalong.data.link

import com.example.readalong.data.transcription.WhistleResult
import com.example.readalong.domain.model.TranscriptSegment
import com.example.readalong.domain.model.TranscriptWord
import com.example.readalong.domain.transcription.TranscriptionInput
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptPackageWriterTest {
    private fun segment(start: Long, text: String, vararg words: Pair<String, Long>) = TranscriptSegment("b:c:$start", "b", "c", start, words.last().second + 300, text, .9f,
        words.map { (w, at) -> TranscriptWord(w, at, at + 300, .9f) })

    @Test fun phoneTextReadsBackTheSameOnTheComputer() {
        // A unit 60–90 s into the book; the computer reads it with the same WhistleResult the phone uses.
        val segments = listOf(
            segment(61_000, "It was a bright cold day.", "It" to 61_000, "was" to 61_400, "a" to 61_800, "bright" to 62_000, "cold" to 62_500, "day." to 63_000),
            segment(64_000, "The clocks struck thirteen.", "The" to 64_000, "clocks" to 64_400, "struck" to 65_000, "thirteen." to 65_600),
        )
        val result = TranscriptPackageWriter.chunkResult(segments, 60_000, 30_000)
        val read = WhistleResult.segments(result.toString(), TranscriptionInput("b", "c", "file", 60_000, 90_000, false))
        assertEquals(listOf("It was a bright cold day.", "The clocks struck thirteen."), read.map { it.text })
        assertEquals(segments.flatMap { it.words }.map { it.word to it.startMs }, read.flatMap { it.words }.map { it.word to it.startMs })
    }

    @Test fun editedTextFallsBackToTheWords() {
        val edited = segment(1_000, "Completely different words.", "Hello" to 1_000, "there." to 1_500)
        val result = TranscriptPackageWriter.chunkResult(listOf(edited), 0, 30_000)
        assertEquals("Hello there.", result.getString("text"))
        assertEquals(2, WhistleResult.segments(result.toString(), TranscriptionInput("b", "c", "file", 0, 30_000, false)).flatMap { it.words }.size)
    }

    @Test fun silentUnitIsEmpty() {
        val result = TranscriptPackageWriter.chunkResult(emptyList(), 0, 30_000)
        assertEquals("", result.getString("text"))
        assertEquals(0, result.getJSONArray("words").length())
    }
}
