package com.example.readalong.data

import com.example.readalong.data.processing.TranscriptionPlan
import com.example.readalong.domain.transcription.TranscriptionInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** Units the way [TranscriptionPlan.inputs] cuts them: 30 s steps per file, book-wide clock. */
    private fun files(vararg lengths: Long, chapterAt: Long? = null) = buildList {
        var offset = 0L
        lengths.forEachIndexed { file, length ->
            var position = 0L
            while (position < length) {
                val end = minOf(position + 30_000, length, chapterAt?.takeIf { file == 0 && it > position } ?: Long.MAX_VALUE)
                add(TranscriptionInput("book", if (chapterAt != null && file == 0 && position >= chapterAt) "chapter-b" else "chapter-$file",
                    "content://file$file", offset + position, offset + end, false, position))
                position = end
            }
            offset += length
        }
    }

    @Test fun correctedLengthKeepsProgress() {
        // The player finds the single file 40 ms longer than its metadata said.
        assertEquals(TranscriptionPlan.Carry(50, null), TranscriptionPlan.carryOver(files(3_000_000), 50, files(3_000_040)))
    }

    @Test fun smallShiftLeavesLaterFilesAlone() {
        // File 1 is 20 ms longer; file 2's saved text is 20 ms early, which is fine. The chunk across the old end is redone.
        assertEquals(TranscriptionPlan.Carry(30, null), TranscriptionPlan.carryOver(files(600_000, 600_000), 30, files(600_020, 600_000)))
    }

    @Test fun largeShiftRedoesOnlyLaterFiles() {
        assertEquals(TranscriptionPlan.Carry(20, 600_000), TranscriptionPlan.carryOver(files(600_000, 600_000), 30, files(602_000, 600_000)))
        // Work that hasn't reached the changed part is all kept.
        assertEquals(TranscriptionPlan.Carry(10, null), TranscriptionPlan.carryOver(files(600_000, 600_000), 10, files(602_000, 600_000)))
    }

    @Test fun changedFilesOrChaptersStartOver() {
        assertNull(TranscriptionPlan.carryOver(files(600_000, 600_000), 30, files(600_000)))
        assertNull(TranscriptionPlan.carryOver(files(600_000), 10, files(600_000, chapterAt = 45_000)))
    }

    /** The v1 encoder, kept verbatim so saved checkpoints from 0.35.2 and earlier stay covered. */
    private fun legacy(inputs: List<TranscriptionInput>): String = org.json.JSONArray().apply { inputs.forEach { input ->
        put(org.json.JSONObject().put("book", input.bookId).put("chapter", input.chapterId).put("uri", input.audioUri)
            .put("start", input.startMs).put("end", input.endMs).put("demo", input.isDemo).put("fileStart", input.fileStartMs))
    } }.toString()
}
