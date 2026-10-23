package com.example.readalong.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.data.book.RoomBookRepository
import com.example.readalong.data.epub.GeneratedEpubRepository
import com.example.readalong.data.local.*
import com.example.readalong.data.processing.*
import com.example.readalong.data.transcription.SpeechModelStore
import com.example.readalong.domain.model.TranscriptSegment
import com.example.readalong.domain.transcription.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class WholeBookPipelineTest {
    private suspend fun fixture(database: ReadAlongDatabase): String {
        val seed = RoomBookRepository(database, seedSamples = true).observeBooks().first().first().id
        val id = "full-book-${System.nanoTime()}"
        database.bookDao().upsertBooks(listOf(database.bookDao().findBook(seed)!!.copy(id = id, isDemo = false, durationMs = 75_000)))
        database.bookDao().insertProgress(listOf(BookProgressEntity(id)))
        database.mediaDao().putAudio(listOf(AudioFileEntity("$id-file", id, "content://original-test/audio", "Original test", 0, 75_000, null)))
        return id
    }
    private val engine = object : TranscriptionEngine {
        override val id = SpeechModelStore.ID
        override fun transcribe(input: TranscriptionInput) = flow {
            emit(TranscriptSegment("${input.bookId}:${input.startMs}", input.bookId, input.chapterId, input.startMs, input.endMs, "Original test passage ${input.startMs}."))
        }
    }
    @Test fun batchYieldPublishesEachCheckpointAndResumeDoesNotRepeatIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        try {
            val id = fixture(database)
            database.processingDao().put(ProcessingJobEntity(id, "batch", engine.id, "QUEUED", 0, 3, null, 0))
            val runner = ProcessingRunner(database, GeneratedEpubRepository(context, database))
            val checkpoints = mutableListOf<Int>()
            assertFalse(runner.run(id, "batch", engine, maxUnits = 4) {
                val done = database.processingDao().find(id)!!.completedUnits
                checkpoints += done
                assertTrue(database.readingDao().generatedChapters(id).isNotEmpty())
                false // Priority, time or thermal policy asks to yield after this commit.
            })
            assertEquals(listOf(1), checkpoints)
            assertTrue(runner.run(id, "batch", engine, maxUnits = 4) {
                checkpoints += database.processingDao().find(id)!!.completedUnits
                true
            })
            assertEquals(listOf(1, 2, 3), checkpoints)
            assertEquals(3, database.readingDao().count(id))
        } finally { database.close() }
    }
    @Test fun archiveBoundaryInsideABatchIsPublishedWhenTheBatchYields() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        try {
            val id = fixture(database)
            database.mediaDao().duration("$id-file", 390_000)
            database.processingDao().put(ProcessingJobEntity(id, "batch", engine.id, "QUEUED", 0, 13, null, 0))
            val runner = ProcessingRunner(database, GeneratedEpubRepository(context, database))
            repeat(3) { assertFalse(runner.run(id, "batch", engine, maxUnits = 4)) }
            val version = database.readingDao().findVersion(id)!!
            assertEquals(12, database.processingDao().find(id)!!.completedUnits)
            // The tenth unit was inside the last batch, not at its end.
            assertEquals(version.transcriptRevision, version.generatedRevision)
        } finally { database.close() }
    }
    @Test fun boundedSessionsReachBeyondOneMinuteAndResumeWithoutRepeatingCommittedUnits() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        try {
            val id = fixture(database)
            database.bookDao().updateListeningPosition(id, 42_123, 0)
            database.processingDao().put(ProcessingJobEntity(id, "work", engine.id, "QUEUED", 0, 3, null, 0))
            val runner = ProcessingRunner(database, GeneratedEpubRepository(context, database))
            assertFalse(runner.run(id, "work", engine, maxUnits = 1))
            val checkpoint = database.processingDao().find(id)!!
            assertEquals(1, checkpoint.completedUnits)
            assertNotNull(checkpoint.planJson)
            assertNotNull(checkpoint.sourceFingerprint)
            assertFalse(runner.run(id, "work", engine, maxUnits = 1))
            assertTrue(runner.run(id, "work", engine, maxUnits = 1))
            assertEquals("READY", database.processingDao().find(id)?.state)
            assertEquals(3, database.readingDao().count(id))
            assertEquals(75_000L, database.readingDao().segments(id).last().endMs)
            assertEquals(42_123L, database.bookDao().progress(id)?.audioPositionMs)
            val version = database.readingDao().findVersion(id)!!
            assertEquals(version.transcriptRevision, version.generatedRevision)
        } finally { database.close() }
    }
    @Test fun changedDurationInvalidatesTheCheckpointInsteadOfSkippingDifferentAudio() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        try {
            val id = fixture(database)
            database.processingDao().put(ProcessingJobEntity(id, "work", engine.id, "QUEUED", 0, 3, null, 0))
            val runner = ProcessingRunner(database, GeneratedEpubRepository(context, database))
            assertFalse(runner.run(id, "work", engine, maxUnits = 1))
            val saved = database.readingDao().findVersion(id)?.generatedUri
            database.importedEpubDao().put(ImportedEpubEntity(id, "reference", "file://reference.epub", "Original reference", "ALIGNED", "Matched", "EPUB", 1, 1, 1, 12, 12, 0))
            database.importedEpubDao().putBlocks(listOf(ImportedEpubBlockEntity(id, "reference", 0, 0, "chapter.xhtml#p", "Original reference words", "P", "[]", false, "[]", 0, 30_000, 1f)))
            database.mediaDao().duration("$id-file", 85_000)
            assertTrue(runCatching { runner.run(id, "work", engine, maxUnits = 1) }.isFailure)
            assertEquals("FAILED", database.processingDao().find(id)?.state)
            assertEquals(1, database.processingDao().find(id)?.completedUnits)
            assertEquals(1, database.readingDao().count(id))
            assertEquals(saved, database.readingDao().findVersion(id)?.generatedUri)
            assertEquals("Original reference words", database.importedEpubDao().block(id, "reference", 0)?.text)
            assertEquals(-1L, database.importedEpubDao().block(id, "reference", 0)?.startMs)
            assertEquals(0, database.importedEpubDao().find(id)?.matchedSegments)
            assertTrue(com.example.readalong.data.epub.ImportedEpubRepository(context, database).align(id))
            assertEquals(-1L, database.importedEpubDao().block(id, "reference", 0)?.startMs)
        } finally { database.close() }
    }
    @Test fun completedChapterIsPublishedWhileLaterChaptersRemainQueued() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        try {
            val id = fixture(database)
            database.mediaDao().putChapters(listOf(
                AudioChapterEntity("$id-one", "$id-file", "At the river", 0, 0, 30_000),
                AudioChapterEntity("$id-two", "$id-file", "Across the bridge", 1, 30_000, 75_000)))
            database.processingDao().put(ProcessingJobEntity(id, "work", engine.id, "QUEUED", 0, 3, null, 0))
            val runner = ProcessingRunner(database, GeneratedEpubRepository(context, database))
            assertFalse(runner.run(id, "work", engine, maxUnits = 1))
            val partial = database.readingDao().findVersion(id)!!
            assertEquals("PARTIAL_READY", partial.state)
            assertEquals(1, partial.completedChapters)
            assertEquals(2, partial.totalChapters)
            assertNotNull(partial.generatedUri)
            assertEquals(listOf("$id-one"), database.readingDao().generatedChapters(id).map { it.chapterId })
            val page = database.readingDao().generatedPaging(id).load(androidx.paging.PagingSource.LoadParams.Refresh<Int>(null, 40, false)) as androidx.paging.PagingSource.LoadResult.Page
            assertEquals(30_000L, page.data.last().endMs)
            assertTrue(runner.run(id, "work", engine))
            val ready = database.readingDao().findVersion(id)!!
            assertEquals(2, ready.completedChapters)
            assertNotEquals(partial.activeEditionId, ready.activeEditionId)
        } finally { database.close() }
    }

}
