package com.example.readalong.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.local.*
import com.example.readalong.data.processing.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueuePriorityRecoveryTest {
    @Test fun singleSlotPriorityAndAutomaticHandoffKeepCheckpointsAndSkipPauses() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val ids = (0..4).map { "priority-${System.nanoTime()}-$it" }
        val launched = mutableListOf<String>()
        val repo = ProcessingRepository(app, db, app.speechModel, launchPreparation = { launched += it.getStringExtra("bookId")!! })
        try {
            ids.forEachIndexed { i, id ->
                db.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false, addedAt = i.toLong())))
                db.mediaDao().putAudio(listOf(AudioFileEntity("$id-file", id, "content://original/$id", "Chapter", 0, 60_000, null)))
                repo.imported(id)
            }
            repo.addAllToQueue()
            assertTrue(launched.isEmpty())
            repo.advanceQueue()
            assertTrue("An idle Add all must wait for Play", launched.isEmpty())
            assertEquals(5, db.processingDao().registeredQueue().size)
            repo.playQueued(ids[0])
            assertEquals(listOf(ids[0]), launched)
            db.processingDao().put(db.processingDao().find(ids[0])!!.copy(completedUnits = 1))
            repo.playQueued(ids[3])
            assertEquals(ids[3], db.processingDao().registeredQueue().first().bookId)
            assertEquals("WAITING_QUEUE", db.processingDao().find(ids[0])!!.state)
            assertEquals(1, db.processingDao().find(ids[0])!!.completedUnits)
            assertEquals(1, db.processingDao().unfinished().size)
            repo.cancel(ids[3])
            val beforePauseHandoff = launched.size
            repo.advanceQueue()
            assertEquals("Pause must not start another book", beforePauseHandoff, launched.size)
            assertTrue(db.processingDao().unfinished().isEmpty())
            assertEquals("CANCELLED", db.processingDao().find(ids[3])!!.state)
            repo.playQueued(ids[0])
            assertEquals("A promoted waiting book must resume its saved chunk", 1, db.processingDao().find(ids[0])!!.completedUnits)
            db.processingDao().put(db.processingDao().find(ids[0])!!.copy(state = "READY"))
            repo.advanceQueue()
            assertEquals(ids[1], launched.last())
            assertEquals(1, db.processingDao().unfinished().size)
            assertEquals("CANCELLED", db.processingDao().find(ids[3])!!.state)
        } finally {
            ids.forEach { repo.cancel(it) }
            db.close()
        }
    }
    @Test fun upgradeDefersExtraActiveBooksAndAutomaticallyResumesTheirExistingChunks() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val ids = (0..2).map { "migrate-${System.nanoTime()}-$it" }
        val repo = ProcessingRepository(app, db, app.speechModel, launchPreparation = {})
        try {
            ids.forEachIndexed { i, id ->
                db.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false)))
                db.mediaDao().putAudio(listOf(AudioFileEntity("$id-file", id, "content://original/$id", "Chapter", 0, 60_000, null)))
                db.processingDao().put(ProcessingJobEntity(id, java.util.UUID.randomUUID().toString(), com.example.readalong.data.transcription.SpeechModelStore.ID,
                    "PROCESSING", 1, 2, null, 0, TranscriptionPlan.encode(TranscriptionPlan.inputs(db, id)), TranscriptionPlan.fingerprint(db, id), i.toLong()))
            }
            repo.recover()
            assertEquals(1, db.processingDao().unfinished().size)
            assertEquals(listOf("WAITING_QUEUE", "WAITING_QUEUE"), ids.drop(1).map { db.processingDao().find(it)!!.state })
            assertTrue(ids.all { db.processingDao().find(it)!!.completedUnits == 1 })
            db.processingDao().put(db.processingDao().find(ids.first())!!.copy(state = "READY"))
            repo.advanceQueue()
            assertEquals("QUEUED", db.processingDao().find(ids[1])!!.state)
            assertEquals(1, db.processingDao().find(ids[1])!!.completedUnits)
            assertEquals(1, db.processingDao().unfinished().size)
        } finally { ids.forEach { repo.cancel(it) }; db.close() }
    }
    @Test fun interruptedIntentSchedulesCheckpointRecoveryButExplicitPauseNeverDoes() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val id = "guard-${System.nanoTime()}"
        val repo = ProcessingRepository(app, db, app.speechModel, launchPreparation = {})
        try {
            db.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false)))
            db.mediaDao().putAudio(listOf(AudioFileEntity("$id-file", id, "content://original/$id", "Chapter", 0, 60_000, null)))
            repo.imported(id); repo.transcribeNext(id)
            val before = db.processingDao().find(id)!!.copy(completedUnits = 1)
            db.processingDao().put(before)
            assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork("readable-recovery:$id").get().any { !it.state.isFinished })
            repo.recoverInterrupted(id, before.workId)
            val resumed = db.processingDao().find(id)!!
            assertNotEquals(before.workId, resumed.workId)
            assertEquals(before.planJson, resumed.planJson)
            assertEquals(1, resumed.completedUnits)
            repo.cancel(id)
            val paused = db.processingDao().find(id)!!
            repo.recoverInterrupted(id, paused.workId)
            assertEquals(paused, db.processingDao().find(id))
        } finally { repo.cancel(id); db.close() }
    }
}
