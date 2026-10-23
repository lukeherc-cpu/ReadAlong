package com.example.readalong.data

import android.content.Context
import com.example.readalong.data.local.BookProgressEntity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.data.book.RoomBookRepository
import com.example.readalong.data.epub.GeneratedEpubRepository
import com.example.readalong.data.reading.TranscriptRepository
import com.example.readalong.data.reading.DemoTranscript
import com.example.readalong.data.local.ReadAlongDatabase
import kotlinx.coroutines.*
import androidx.room.withTransaction
import com.example.readalong.data.local.TranscriptEntity
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class GeneratedEpubTest {
    @Test fun eachChunkUpdatesReadingWithoutRewritingTheArchiveOrChangingEditionIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        try {
            val id = RoomBookRepository(db, seedSamples = true).observeBooks().first().first().id
            val dao = db.readingDao()
            dao.putSegments(listOf(TranscriptEntity("first", id, "chapter", 1000, 2000, "First sentence.", null, null)))
            dao.putVersion(com.example.readalong.data.local.ReadableVersionEntity(id, "PARTIAL_READY", null, 0, 1, null, 0, transcriptRevision = 1))
            val epub = GeneratedEpubRepository(context, db)
            val archive = epub.generate(id)
            val edition = dao.findVersion(id)!!.activeEditionId!!
            dao.putSegments(listOf(TranscriptEntity("second", id, "chapter", 31_000, 32_000, "Newly completed sentence.", null, null)))
            dao.putVersion(dao.findVersion(id)!!.copy(transcriptRevision = 2))
            epub.publishReadingRange(com.example.readalong.domain.transcription.TranscriptionInput(id, "chapter", null, 30_000, 60_000, true))
            assertEquals(edition, dao.findVersion(id)!!.activeEditionId)
            assertEquals(archive.toString(), dao.findVersion(id)!!.generatedUri)
            assertEquals(1L, dao.findVersion(id)!!.generatedRevision)
            assertTrue(dao.editionPage(id, edition, 80, 0).any { "Newly completed sentence." in it.text })
            epub.refreshReading(id)
            assertEquals("Opening the reader uses the current projection", edition, dao.findVersion(id)!!.activeEditionId)
            epub.generate(id)
            assertEquals(2L, dao.findVersion(id)!!.generatedRevision)
        } finally { db.close() }
    }
    @Test fun generatedFileIsParsedInternallyAndCanonicalTranscriptRemainsIntact() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        try {
            val seed = RoomBookRepository(database, seedSamples = true).observeBooks().first().first().id
            val bookId = "demo-test-${System.nanoTime()}"
            database.bookDao().upsertBooks(listOf(database.bookDao().findBook(seed)!!.copy(id = bookId)))
            database.bookDao().insertProgress(listOf(BookProgressEntity(bookId)))
            val transcripts = TranscriptRepository(database)
            transcripts.prepareDemo(bookId)
            val before = database.readingDao().segments(bookId)
            val uri = GeneratedEpubRepository(context, database).generate(bookId)
            assertTrue(uri.toString().startsWith("content://"))
            context.contentResolver.openInputStream(uri).use { assertNotNull(it); assertEquals('P'.code, it!!.read()) }
            val version = database.readingDao().findVersion(bookId)!!
            assertEquals(uri.toString(), version.generatedUri)
            assertEquals("DEMO_READY", version.state)
            assertEquals(version.transcriptRevision, version.generatedRevision)
            assertNotNull(version.activeEditionId)
            assertEquals(uri, GeneratedEpubRepository(context, database).generate(bookId))
            assertEquals(before, database.readingDao().segments(bookId))
            assertEquals(DemoTranscript.segments(bookId).size, database.readingDao().count(bookId))
            // Saved paragraphs own their timing and retain every original segment anchor.
            val paragraphs = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM epub_paragraphs WHERE bookId = ?", arrayOf(bookId))
            paragraphs.use { assertTrue(it.moveToFirst()); assertTrue(it.getInt(0) in 1..18) }
            val page = database.readingDao().generatedPaging(bookId).load(androidx.paging.PagingSource.LoadParams.Refresh<Int>(null, 80, false)) as androidx.paging.PagingSource.LoadResult.Page
            assertEquals(18, page.data.sumOf { org.json.JSONArray(it.anchorsJson).length() })
            database.readingDao().clearTranscript(bookId)
            val saved = database.readingDao().generatedPaging(bookId).load(androidx.paging.PagingSource.LoadParams.Refresh<Int>(null, 80, false)) as androidx.paging.PagingSource.LoadResult.Page
            assertEquals(page.data, saved.data)
            assertTrue(saved.data.all { it.endMs > it.startMs })

        } finally { database.close() }
    }
    @Test fun staleEditionIsNotPublishedAndProgressSavesDuringIndexing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        try {
            val seed = RoomBookRepository(database, seedSamples = true).observeBooks().first().first().id
            val id = "demo-test-${System.nanoTime()}"
            database.bookDao().upsertBooks(listOf(database.bookDao().findBook(seed)!!.copy(id = id)))
            database.bookDao().insertProgress(listOf(BookProgressEntity(id)))
            TranscriptRepository(database).prepareDemo(id)
            val epub = GeneratedEpubRepository(context, database)
            val originalUri = epub.generate(id)
            val dao = database.readingDao()
            database.withTransaction {
                dao.clearTranscript(id)
                repeat(60) { batch -> dao.putSegments(List(100) { offset ->
                    val index = batch * 100 + offset
                    TranscriptEntity("$id-large-$index", id, "long-chapter", index * 1000L, (index + 1) * 1000L,
                        "Original test paragraph $index. " + "The lantern shone beside the river. ".repeat(8), null, null)
                }) }
                dao.putVersion(dao.findVersion(id)!!.copy(transcriptRevision = 1))
            }
            val generation = async { runCatching { epub.generate(id) } }
            withTimeout(10_000) {
                while (database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM epub_paragraphs WHERE bookId = ? AND editionId != ?",
                    arrayOf(id, dao.findVersion(id)!!.activeEditionId)).use { it.moveToFirst(); it.getInt(0) } == 0) {
                    check(!generation.isCompleted) { "Generation completed before the concurrent check" }
                    delay(5)
                }
            }
            withTimeout(2000) { database.withTransaction {
                database.bookDao().updateListeningPosition(id, 123456, 99)
                dao.putVersion(dao.findVersion(id)!!.copy(transcriptRevision = 2))
            } }
            assertTrue(generation.await().isFailure)
            assertEquals(123456L, database.bookDao().progress(id)?.audioPositionMs)
            assertEquals(originalUri.toString(), dao.findVersion(id)?.generatedUri)
            assertEquals(0L, dao.findVersion(id)?.generatedRevision)
            context.contentResolver.openInputStream(originalUri).use { assertEquals('P'.code, it!!.read()) }
            assertTrue(dao.editionCount(id, dao.findVersion(id)!!.activeEditionId!!) in 1..18)
        } finally { database.close() }
    }

}
