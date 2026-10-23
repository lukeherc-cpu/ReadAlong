package com.example.readalong.data

import android.content.Context
import com.example.readalong.data.local.BookProgressEntity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.book.RoomBookRepository
import com.example.readalong.data.epub.GeneratedEpubRepository
import com.example.readalong.data.local.*
import com.example.readalong.data.processing.*
import com.example.readalong.data.reading.TranscriptRepository
import com.example.readalong.data.reading.toSegment
import com.example.readalong.domain.transcription.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ProcessingPipelineTest {
    @Test fun failedRealBookReopensItsDiskCheckpointAndResumesOnlyUnfinishedAudio() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "checkpoint-reopen-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, ReadAlongDatabase::class.java, name).build()
        var db = open()
        val id = "checkpoint-real-book"
        val starts = mutableListOf<Long>()
        fun engine(fail: Boolean) = object : TranscriptionEngine {
            override val id = com.example.readalong.data.transcription.SpeechModelStore.ID
            override fun transcribe(input: TranscriptionInput) = flow {
                starts += input.startMs
                if (fail && input.startMs >= 30_000) throw IllegalStateException("Injected recognition failure")
                val start = input.startMs + 1000
                emit(com.example.readalong.domain.model.TranscriptSegment("$id:$start", input.bookId, input.chapterId,
                    start, start + 1000, "Saved sentence.", words = listOf(
                        com.example.readalong.domain.model.TranscriptWord("Saved", start, start + 400),
                        com.example.readalong.domain.model.TranscriptWord("sentence.", start + 500, start + 1000))))
            }
        }
        try {
            db.bookDao().insertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false)))
            db.bookDao().insertProgress(listOf(BookProgressEntity(id, audioPositionMs = 42_000)))
            db.mediaDao().putAudio(listOf(AudioFileEntity("checkpoint-file", id, "content://local/checkpoint-fixture", "Fixture", 0, 90_000, null)))
            val plan = TranscriptionPlan.encode(TranscriptionPlan.inputs(db, id))
            db.processingDao().put(ProcessingJobEntity(id, "first", com.example.readalong.data.transcription.SpeechModelStore.ID,
                "QUEUED", 0, 3, null, 0, plan, TranscriptionPlan.fingerprint(db, id)))
            assertTrue(runCatching { ProcessingRunner(db, GeneratedEpubRepository(context, db)).run(id, "first", engine(true)) }.isFailure)
            val checkpoint = db.processingDao().find(id)!!
            val saved = db.readingDao().segments(id)
            assertEquals("FAILED", checkpoint.state); assertEquals(1, checkpoint.completedUnits)
            assertEquals(listOf(0L, 30_000L), starts)
            assertEquals(1, saved.size)
            db.close(); db = open()
            assertEquals(checkpoint, db.processingDao().find(id))
            assertEquals(saved, db.readingDao().segments(id))
            // Simulate a stopped derived-edition publication. The committed transcript and
            // checkpoint are authoritative even when the reader view needs repair.
            db.bookDao().removeMetadata("live_reading_revision:$id")
            db.processingDao().put(checkpoint.copy(workId = "resumed", state = "QUEUED"))
            starts.clear()
            ProcessingRunner(db, GeneratedEpubRepository(context, db)).run(id, "resumed", engine(false))
            assertEquals(listOf(30_000L, 60_000L), starts)
            assertEquals("READY", db.processingDao().find(id)!!.state)
            assertEquals(3, db.processingDao().find(id)!!.completedUnits)
            assertEquals(saved.single(), db.readingDao().segments(id).first())
            assertEquals(42_000L, db.bookDao().progress(id)!!.audioPositionMs)
            assertEquals(1000L, com.example.readalong.domain.reading.sentenceCues(saved.single().toSegment()).single().audioStartMs)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun checkpointSurvivesInterruptionAndFailuresPreserveListeningAndReading() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        try {
            val seed = RoomBookRepository(database, seedSamples = true).observeBooks().first().first().id
            val bookId = "demo-test-${System.nanoTime()}"
            database.bookDao().upsertBooks(listOf(database.bookDao().findBook(seed)!!.copy(id = bookId)))
            database.bookDao().insertProgress(listOf(BookProgressEntity(bookId)))
            TranscriptRepository(database).prepareDemo(bookId)
            database.bookDao().updateListeningPosition(bookId, 42_000, 123)
            val original = database.readingDao().segments(bookId)
            val epub = GeneratedEpubRepository(context, database)
            val originalUri = epub.generate(bookId)
            database.processingDao().put(ProcessingJobEntity(bookId, "first", "sample-v1", "QUEUED", 0, 3, null, 0))
            val run = launch { ProcessingRunner(database, epub).run(bookId, "first", SampleTranscriptionEngine(20)) }
            withTimeout(10_000) { database.processingDao().observe(bookId).first { (it?.completedUnits ?: 0) >= 1 } }
            run.cancelAndJoin() // Simulates an OS stop; persisted checkpoint is retained.
            val checkpoint = database.processingDao().find(bookId)!!
            assertTrue(checkpoint.completedUnits >= 1)
            assertEquals(originalUri.toString(), database.readingDao().findVersion(bookId)?.generatedUri)
            database.processingDao().put(checkpoint.copy(workId = "resumed", state = "QUEUED"))
            ProcessingRunner(database, epub).run(bookId, "resumed", SampleTranscriptionEngine(0))
            assertEquals("READY", database.processingDao().find(bookId)?.state)
            assertEquals(3, database.processingDao().find(bookId)?.completedUnits)
            assertEquals(original, database.readingDao().segments(bookId))
            assertEquals(42_000L, database.bookDao().progress(bookId)?.audioPositionMs)
            val ready = database.readingDao().findVersion(bookId)!!
            assertEquals(ready.transcriptRevision, ready.generatedRevision)
            val broken = object : TranscriptionEngine {
                override val id = "sample-v1"
                override fun transcribe(input: TranscriptionInput) = flow<com.example.readalong.domain.model.TranscriptSegment> { error("Fixture failure") }
            }
            database.processingDao().put(checkpoint.copy(workId = "broken", state = "QUEUED", completedUnits = 0))
            assertTrue(runCatching { ProcessingRunner(database, epub).run(bookId, "broken", broken) }.isFailure)
            assertEquals("FAILED", database.processingDao().find(bookId)?.state)
            assertEquals(ready.generatedUri, database.readingDao().findVersion(bookId)?.generatedUri)
            assertEquals(original, database.readingDao().segments(bookId))
            assertEquals(42_000L, database.bookDao().progress(bookId)?.audioPositionMs)
            assertTrue(runCatching { SampleTranscriptionEngine(0).transcribe(TranscriptionInput("imported", "one", "content://audio", 0, 1000, false)).toList() }.isFailure)
        } finally { database.close() }
    }

    @Test fun temporaryFailuresRetryTheSameCheckpointAndKeepDurableBudget() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        try {
            val id = RoomBookRepository(db, seedSamples = true).observeBooks().first().first().id
            val epub = GeneratedEpubRepository(context, db)
            db.processingDao().put(ProcessingJobEntity(id, "retry", "sample-v1", "QUEUED", 0, 3, null, 0))
            var calls = 0
            val engine = object : TranscriptionEngine {
                override val id = "sample-v1"
                override fun transcribe(input: TranscriptionInput) = flow {
                    if (++calls == 1) throw java.io.IOException("Temporary recognition disconnect")
                    emitAll(SampleTranscriptionEngine(0).transcribe(input))
                }
            }
            val failure = runCatching { ProcessingRunner(db, epub).run(id, "retry", engine, 1) }.exceptionOrNull()!!
            assertEquals("QUEUED", db.processingDao().find(id)!!.state)
            assertEquals(0, db.processingDao().find(id)!!.completedUnits)
            assertTrue(PreparationRetry.record(db, id, "retry", failure))
            ProcessingRunner(db, epub).run(id, "retry", engine, 1)
            assertEquals(1, db.processingDao().find(id)!!.completedUnits)
            assertEquals(2, calls)
            // A later checkpoint receives its own retry budget, regardless of scheduler waits.
            assertTrue(PreparationRetry.record(db, id, "retry", failure))
            assertTrue(PreparationRetry.record(db, id, "retry", failure))
            assertFalse(PreparationRetry.record(db, id, "retry", failure))
            assertFalse(PreparationRetry.record(db, id, "superseded", failure))
            val job = db.processingDao().find(id)!!
            db.processingDao().put(job.copy(state = "CANCELLED"))
            assertFalse(PreparationRetry.record(db, id, "retry", failure))
            assertEquals("CANCELLED", db.processingDao().find(id)!!.state)
            assertFalse(PreparationRetry.transient(java.io.FileNotFoundException()))
            assertFalse(PreparationRetry.transient(SecurityException()))
            assertFalse(PreparationRetry.transient(com.example.readalong.data.transcription.ModelUnavailableException("Missing model")))
            assertFalse(PreparationRetry.transient(CancellationException()))
        } finally { db.close() }
    }

    @Test fun workManagerCompletesAQueuedSampleAndRealImportsWaitForAModel() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val bookId = "demo-worker-${System.nanoTime()}"
        app.database.bookDao().insertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = bookId)))
        app.database.bookDao().insertProgress(listOf(BookProgressEntity(bookId)))
        try {
            app.processing.prepare(bookId)
            withTimeout(30_000) { app.processing.observe(bookId).first { it?.state == "READY" } }
            assertNotNull(app.database.readingDao().findVersion(bookId)?.generatedUri)
            // Simulate process death after Room records continuation but before WorkManager enqueue.
            val orphanId = java.util.UUID.randomUUID().toString()
            app.database.processingDao().put(app.database.processingDao().find(bookId)!!.copy(workId = orphanId, state = "QUEUED", completedUnits = 1))
            app.processing.recover()
            withTimeout(30_000) { app.processing.observe(bookId).first { it?.state == "READY" } }
            assertNotEquals(orphanId, app.database.processingDao().find(bookId)?.workId)
            app.processing.prepare(bookId)
            app.processing.cancel(bookId)
            assertEquals("CANCELLED", app.database.processingDao().find(bookId)?.state)
            app.processing.prepare(bookId)
            withTimeout(30_000) { app.processing.observe(bookId).first { it?.state == "READY" } }
            val fixture = createTestWave(app, "Processing-${System.nanoTime()}.wav", 2)
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", fixture)
            val imported = app.audioImporter.importFiles(listOf(uri), false).bookIds.single()
            try {
                assertEquals("WAITING_SELECTION", app.database.processingDao().find(imported)?.state)
                assertEquals(0, app.database.readingDao().count(imported))
                assertEquals("AVAILABLE", app.database.bookDao().findBook(imported)?.mediaState)
            } finally { app.processing.cancel(imported); app.database.bookDao().removeBook(imported); fixture.delete() }
        } finally { app.processing.cancel(bookId); app.database.bookDao().removeBook(bookId) }
    }
}
