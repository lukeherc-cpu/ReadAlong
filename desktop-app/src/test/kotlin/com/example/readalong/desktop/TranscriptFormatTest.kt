package com.example.readalong.desktop

import com.example.readalong.data.transcription.WhistleResult
import com.example.readalong.desktop.core.AudioPart
import com.example.readalong.desktop.core.Chapter
import com.example.readalong.desktop.core.LibraryBook
import com.example.readalong.desktop.core.Scanner
import com.example.readalong.desktop.reading.BookTextBuilder
import com.example.readalong.desktop.transcription.TranscriptStore
import com.example.readalong.desktop.transcription.transcriptionPlan
import com.example.readalong.domain.transcription.TranscriptionInput
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptFormatTest {
    /** whisper.cpp's token JSON, as readalong_whisper.cpp (and the phone's whisper_jni.cpp) writes it. */
    private val whisper = JSONArray().put(JSONObject().put("startMs", 0).put("endMs", 2600).put("text", " The quick brown fox.").put("noSpeech", 0.01)
        .put("tokens", JSONArray().apply {
            listOf(" The" to 120L, " quick" to 400L, " brown" to 800L, " fox" to 1300L, "." to 1700L).forEach { (t, dtw) ->
                put(JSONObject().put("text", t).put("startMs", dtw - 50).put("endMs", dtw + 250).put("dtwMs", dtw).put("confidence", .9))
            }
        })).toString()

    @Test fun chunkResultIsWhatThePhoneReads() {
        val result = TranscriptStore.chunkResult(whisper, 30_000)
        assertEquals("The quick brown fox.", result.getString("text"))
        val words = result.getJSONArray("words")
        assertEquals(4, words.length())
        assertEquals("fox.", words.getJSONObject(3).getString("word"))
        assertEquals(1.3, words.getJSONObject(3).getDouble("start"), 1e-9)
        // The phone's importer reads the same object with WhistleResult.
        val segments = WhistleResult.segments(result.toString(), TranscriptionInput("b", "c", "x", 60_000, 90_000, false))
        assertEquals(1, segments.size)
        assertEquals(60_120, segments[0].startMs)
        assertEquals(listOf("The", "quick", "brown", "fox."), segments[0].words.map { it.word })
    }

    @Test fun silenceIsAnEmptyChunk() {
        val result = TranscriptStore.chunkResult("[]", 30_000)
        assertEquals("", result.getString("text"))
        assertEquals(0, result.getJSONArray("words").length())
    }

    @Test fun planMatchesThePhone() {
        val book = LibraryBook("id", "T", null, "k", null, listOf(AudioPart("a", 75_000, 0, 1, 1), AudioPart("b", 40_000, 75_000, 1, 1)),
            listOf(Chapter("One", 0), Chapter("Two", 50_000), Chapter("Three", 75_000)))
        val plan = transcriptionPlan(book)
        assertEquals(listOf(0L to 30_000L, 30_000L to 50_000L, 50_000L to 75_000L), plan.filter { it.file == 0 }.map { it.startMs to it.endMs })
        assertEquals(listOf(0L to 30_000L, 30_000L to 40_000L), plan.filter { it.file == 1 }.map { it.startMs to it.endMs })
    }

    @Test fun generatedTextHasTimedSentences() {
        val book = LibraryBook("id", "T", null, "k", null, listOf(AudioPart("a", 30_000, 0, 1, 1)))
        val result = TranscriptStore.chunkResult(whisper, 30_000)
        val segments = WhistleResult.segments(result.toString(), TranscriptionInput("id", "f0", "a", 0, 30_000, false))
        val text = BookTextBuilder.generated(book, segments)
        val cue = text.paragraphs.single().cues.single()
        assertEquals(120L, cue.audioStartMs)
        assertEquals(0, text.paragraphAt(500))
    }

    @Test fun scannerFollowsThePhoneRules() {
        val root = Files.createTempDirectory("scan").toFile()
        fun touch(path: String) = File(root, path).apply { parentFile.mkdirs(); writeText("x") }
        touch("Author/Book One/CD1/01.mp3"); touch("Author/Book One/CD2/01.mp3"); touch("Author/Book One/Book One.epub")
        touch("Singles/A.m4b"); touch("Singles/B.m4b")
        touch("Parts/Book Part 1.m4b"); touch("Parts/Book Part 2.m4b")
        val found = Scanner.scan(root).associateBy { it.name }
        assertEquals(setOf("Book One", "A", "B", "Parts"), found.keys)
        assertEquals(2, found.getValue("Book One").audio.size)
        assertTrue(found.getValue("Book One").audio[0].path.contains("CD1"))
        assertEquals(1, found.getValue("Book One").epubs.size)
        root.deleteRecursively()
    }
}
