package com.example.readalong.data

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.book.RoomBookRepository
import com.example.readalong.data.local.*
import com.example.readalong.data.processing.ProcessingRepository
import com.example.readalong.data.transcription.*
import com.example.readalong.domain.transcription.SpeechModels
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class QueueSetupTest {
    @Test fun eachExplicitMissingModelRequestKeepsProgressWhileWaitingForAutomaticDownload() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val root = File(app.cacheDir, "missing-model-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "missing.preferences_pb") })
        val model = SpeechModelStore(object : ContextWrapper(app) { override fun getFilesDir() = root }, data)
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val id = "missing-${System.nanoTime()}"
        try {
            db.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false)))
            val repo = ProcessingRepository(app, db, model, launchPreparation = {})
            repo.imported(id)
            repo.transcribeNext(id)
            val first = db.processingDao().find(id)!!
            assertEquals("WAITING_MODEL", first.state)
            db.processingDao().put(first.copy(completedUnits = 2, totalUnits = 8))
            repo.transcribeNext(id)
            val next = db.processingDao().find(id)!!
            assertNotEquals(first.workId, next.workId)
            assertEquals(2, next.completedUnits)
            assertEquals(next.workId, repo.queue.first().single().workId)
            assertEquals("WAITING_MODEL", next.state)
        } finally { db.close(); scope.cancel(); root.deleteRecursively() }
    }
    @Test fun freshSetupDownloadsWhistleAutomaticallyAndHonorsManualCancellation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "setup-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "test.preferences_pb") })
        val store = SpeechModelStore(object : ContextWrapper(context) { override fun getFilesDir() = root }, data)
        try {
            assertEquals(SpeechModels.whistle, store.selectedModel())
            assertTrue(store.needsAutomaticSetup())
            assertTrue(store.needsAutomaticSetup())
            assertEquals(NetworkType.CONNECTED, ModelInstallWorker.request(automatic = true).workSpec.constraints.requiredNetworkType)
            assertEquals(NetworkType.CONNECTED, ModelInstallWorker.request().workSpec.constraints.requiredNetworkType)
            store.select(SpeechModels.whistle.id)
            assertEquals(SpeechModels.whistle, store.selectedModel())
            assertTrue(store.needsAutomaticSetup())
            assertTrue(runCatching { store.select("whisper-small.en-v1") }.isFailure)
            assertEquals(SpeechModels.whistle.id, store.status.first().modelId)
            assertEquals(SpeechModels.whistle.id, ModelInstallWorker.request().workSpec.input.getString("modelId"))
            store.automaticDownloads(false)
            store.remove()
            assertFalse(store.needsAutomaticSetup())
            store.automaticDownloads(true)
            assertTrue(store.needsAutomaticSetup())
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun legacyEnginesRestartWithWhistleWithoutChangingListeningPosition() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val id = "retry-model-${System.nanoTime()}"
        val previous = app.speechModel.selectedModel().id
        try {
            app.speechModel.select(SpeechModels.whistle.id)
            val seed = RoomBookRepository(db, seedSamples = true).observeBooks().first().first().id
            db.bookDao().upsertBooks(listOf(db.bookDao().findBook(seed)!!.copy(id = id, isDemo = false, durationMs = 60_000)))
            db.mediaDao().putAudio(listOf(AudioFileEntity("$id-file", id, "content://original/audio", "Original", 0, 60_000, null)))
            val repo = ProcessingRepository(app, db, app.speechModel, launchPreparation = {})
            repo.imported(id)
            db.processingDao().put(db.processingDao().find(id)!!.copy(state = "FAILED", engineId = "whisper-small.en-v1"))
            repo.transcribeNext(id)
            assertEquals(SpeechModels.whistle.id, db.processingDao().find(id)!!.engineId)
            val previousJob = db.processingDao().find(id)!!
            db.processingDao().put(previousJob.copy(state = "FAILED", engineId = "whisper-small.en-v1", completedUnits = 1))
            repo.transcribeNext(id)
            assertEquals(SpeechModels.whistle.id, db.processingDao().find(id)!!.engineId)
            assertEquals(0, db.processingDao().find(id)!!.completedUnits)
            db.bookDao().insertProgress(listOf(BookProgressEntity(id, 1234, lastPlayedAt = 77)))
            repo.rebuild(id)
            assertEquals(SpeechModels.whistle.id, db.processingDao().find(id)!!.engineId)
            assertEquals(0, db.processingDao().find(id)!!.completedUnits)
            assertEquals(1234L, db.bookDao().progress(id)!!.audioPositionMs)
            assertEquals(77L, db.bookDao().progress(id)!!.lastPlayedAt)
        } finally {
            androidx.work.WorkManager.getInstance(app).cancelUniqueWork("readable:$id").result.get()
            app.speechModel.select(previous); db.close()
        }
    }

    @Test fun collectionImportsStayIdleAndExplicitStartsReplaceTheSingleSlot() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val ids = listOf("selection-first-${System.nanoTime()}", "selection-new-${System.nanoTime()}")
        try {
            val launches = mutableListOf<String>()
            val repo = ProcessingRepository(app, db, app.speechModel, launchPreparation = { launches += it.getStringExtra("bookId")!! })
            ids.forEach { id ->
                db.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false, durationMs = 60_000)))
                db.mediaDao().putAudio(listOf(AudioFileEntity("$id-file", id, "content://original/$id", "Original", 0, 60_000, null)))
                repo.imported(id)
            }
            assertEquals(listOf("WAITING_SELECTION", "WAITING_SELECTION"), ids.map { db.processingDao().find(it)!!.state })
            assertTrue(launches.isEmpty())
            repo.transcribeNext(ids[0])
            assertEquals(listOf(ids[0]), launches)
            val first = db.processingDao().find(ids[0])!!
            db.processingDao().put(first.copy(completedUnits = 1))
            repo.transcribeNext(ids[1])
            assertEquals("WAITING_QUEUE", db.processingDao().find(ids[0])!!.state)
            assertEquals(1, db.processingDao().find(ids[0])!!.completedUnits)
            assertEquals(listOf(ids[0], ids[1]), launches)
            assertEquals("QUEUED", db.processingDao().find(ids[1])!!.state)
            db.processingDao().put(db.processingDao().find(ids[0])!!.copy(state = "READY"))
            repo.transcribeNext(ids[0])
            assertEquals("Selecting completed preparation must not stop another book", "QUEUED", db.processingDao().find(ids[1])!!.state)
            assertEquals(2, launches.size)
        } finally { ids.forEach { androidx.work.WorkManager.getInstance(app).cancelUniqueWork("readable:$it").result.get() }; db.close() }
    }

    @Test fun importsWaitAndPriorityReorderPersistsWithoutChangingProgressOrModel() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val name = "queue-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(app, ReadAlongDatabase::class.java, name).build()
        val ids = listOf("queue-a-${System.nanoTime()}", "queue-b-${System.nanoTime()}")
        try {
            val seed = RoomBookRepository(db, seedSamples = true).observeBooks().first().first().id
            ids.forEachIndexed { index, id -> db.bookDao().upsertBooks(listOf(db.bookDao().findBook(seed)!!.copy(id = id, isDemo = false, addedAt = index.toLong()))) }
            val repo = ProcessingRepository(app, db, app.speechModel, launchPreparation = {})
            ids.forEach { repo.imported(it) }
            assertEquals(listOf("WAITING_SELECTION", "WAITING_SELECTION"), ids.map { db.processingDao().find(it)!!.state })
            assertNull(db.processingDao().nextBook())
            ids.forEachIndexed { index, id -> db.processingDao().put(db.processingDao().find(id)!!.copy(state = "PROCESSING", workId = UUID.randomUUID().toString(), completedUnits = 1, totalUnits = 3, queuePosition = index.toLong())) }
            val old = db.processingDao().find(ids[1])!!
            assertTrue(repo.isTurn(ids[0], db.processingDao().find(ids[0])!!.workId))
            assertFalse(repo.isTurn(ids[1], old.workId))
            repo.move(ids[1], -1)
            assertEquals(ids[1], db.processingDao().nextBook())
            val changed = db.processingDao().find(ids[1])!!
            assertEquals(old.engineId, changed.engineId)
            assertEquals(old.completedUnits, changed.completedUnits)
            assertEquals(old.totalUnits, changed.totalUnits)
            assertTrue(repo.isTurn(ids[1], changed.workId))
            assertFalse(repo.isTurn(ids[0], db.processingDao().find(ids[0])!!.workId))
            db.close()
            db = Room.databaseBuilder(app, ReadAlongDatabase::class.java, name).build()
            assertEquals(listOf(ids[1], ids[0]), db.processingDao().orderedQueue().map { it.bookId })
            assertEquals(1, db.processingDao().find(ids[1])!!.completedUnits)
        } finally {
            ids.forEach { androidx.work.WorkManager.getInstance(app).cancelUniqueWork("readable:$it").result.get() }
            db.close(); app.deleteDatabase(name)
        }
    }
}
