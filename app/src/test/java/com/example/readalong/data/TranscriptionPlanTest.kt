package com.example.readalong.data

import com.example.readalong.data.processing.TranscriptionPlan
import com.example.readalong.domain.transcription.TranscriptionInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionPlanTest {
    private val uri = "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks/document/primary%3AAudiobooks%2FThe%20Count%20of%20Monte%20Cristo.m4b"
    private fun longBook(units: Int) = List(units) { i ->
        TranscriptionInput("book-1", "chapter-${i / 120}", if (i % 500 == 499) null else uri, i * 30_000L, i * 30_000L + 30_000, false, (i % 900) * 30_000L)
    }

    @Test fun compactPlanRoundTrips() {
        val plan = longBook(6_600)
        assertEquals(plan, TranscriptionPlan.decode(TranscriptionPlan.encode(plan)))
        assertEquals(emptyList<TranscriptionInput>(), TranscriptionPlan.decode(TranscriptionPlan.encode(emptyList())))
    }

    @Test fun compactPlanIsMuchSmallerThanLegacy() {
        val plan = longBook(6_600)
        val compact = TranscriptionPlan.encode(plan).length
        val legacy = legacy(plan).length
        assertTrue("compact=$compact legacy=$legacy", compact * 4 < legacy)
        assertTrue("A 55-hour plan must fit well inside a 2 MB CursorWindow: $compact", compact < 600_000)
    }

    @Test fun legacyCheckpointsStillDecode() {
        val plan = longBook(40) + TranscriptionInput("demo", "c", null, 0, 1000, true, 0)
        assertEquals(plan, TranscriptionPlan.decode(legacy(plan)))
        assertEquals(emptyList<TranscriptionInput>(), TranscriptionPlan.decode("[]"))
    }

    /** The v1 encoder, kept verbatim so saved checkpoints from 0.35.2 and earlier stay covered. */
    private fun legacy(inputs: List<TranscriptionInput>): String = org.json.JSONArray().apply { inputs.forEach { input ->
        put(org.json.JSONObject().put("book", input.bookId).put("chapter", input.chapterId).put("uri", input.audioUri)
            .put("start", input.startMs).put("end", input.endMs).put("demo", input.isDemo).put("fileStart", input.fileStartMs))
    } }.toString()
}
