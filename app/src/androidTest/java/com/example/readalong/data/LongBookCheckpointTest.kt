package com.example.readalong.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.epub.GeneratedEpubRepository
import com.example.readalong.data.local.*
import com.example.readalong.data.processing.*
import com.example.readalong.domain.model.TranscriptSegment
import com.example.readalong.domain.transcription.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LongBookCheckpointTest {
    @Test fun a27HourCheckpointDoesNotReadThousandsOfCompletedJobsAgain() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val jobReads = AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java)
            .setQueryCallback({ sql, _ -> if (sql.startsWith("SELECT * FROM processing_jobs")) jobReads.incrementAndGet() }, java.util.concurrent.Executor { it.run() })
            .build()
        try {
            val id = "long-checkpoint"
            val duration = 200_297_508L
            db.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false, durationMs = duration)))
            db.mediaDao().putAudio(listOf(AudioFileEntity("audio", id, "content://fixture/long-book.m4b", "Long book", 0, duration, null)))
            val plan = TranscriptionPlan.inputs(db, id)
            assertTrue(plan.size > 6600)
            val completed = 3240
            db.processingDao().put(ProcessingJobEntity(id, "checkpoint-test", "checkpoint-engine", "QUEUED", completed, plan.size, null, 0,
                TranscriptionPlan.encode(plan), TranscriptionPlan.fingerprint(db, id)))
            db.readingDao().putSegments(listOf(TranscriptEntity("earlier", id, "audio", 0, 1000, "Earlier completed text is kept.", 1f, null)))
            db.readingDao().putVersion(ReadableVersionEntity(id, "PARTIAL_READY", null, 0, 1, null, 0,
                transcriptRevision = 1, activeEditionId = "live", generatedFormat = 1))
            db.bookDao().putMetadata(LibraryMetadataEntity("live_reading_revision:$id", "live:1"))
            var readsBeforeAudio = 0
            var requestedStart = -1L
            val engine = object : TranscriptionEngine {
                override val id = "checkpoint-engine"
                override fun transcribe(input: TranscriptionInput) = flow {
                    readsBeforeAudio = jobReads.get()
                    requestedStart = input.startMs
                    emit(TranscriptSegment("resumed", input.bookId, input.chapterId, input.startMs, input.endMs, "New text after the saved checkpoint."))
                }
            }
            jobReads.set(0)
            val began = android.os.SystemClock.elapsedRealtime()
            assertFalse(ProcessingRunner(db, GeneratedEpubRepository(app, db)).run(id, "checkpoint-test", engine, maxUnits = 1))
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("checkpoint_probe", "reads_before_audio=$readsBeforeAudio total_reads=${jobReads.get()} elapsed_ms=${android.os.SystemClock.elapsedRealtime()-began}")
            })
            assertEquals(97_200_000L, requestedStart)
            assertEquals(completed + 1, db.processingDao().find(id)!!.completedUnits)
            assertNotNull(db.readingDao().segment("earlier"))
            assertNotNull(db.readingDao().segment("resumed"))
            assertTrue("Resume did $readsBeforeAudio full job reads before loading audio; it must start directly at the checkpoint", readsBeforeAudio < 10)
        } finally { db.close() }
    }
}
