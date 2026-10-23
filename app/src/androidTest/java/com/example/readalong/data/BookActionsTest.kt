package com.example.readalong.data

import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.book.*
import com.example.readalong.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BookActionsTest {
    @Test fun productionLibraryIsEmptyAndUpgradeRemovesOnlySampleRows() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        try {
            assertTrue(RoomBookRepository(db).observeBooks().first().isEmpty())
            db.bookDao().removeMetadata("sample_cleanup_v2")
            db.bookDao().insertBooks(DemoBooks.books)
            db.bookDao().insertProgress(DemoBooks.progress)
            db.bookDao().insertBooks(listOf(DemoBooks.books.first().copy(id = "real", isDemo = false)))
            db.bookDao().insertProgress(listOf(BookProgressEntity("real", 1234, false, 42)))
            val books = RoomBookRepository(db).observeBooks().first()
            assertEquals(listOf("real"), books.map { it.id })
            assertEquals(1234L, books.single().positionMs); assertEquals(42L, books.single().lastPlayedAt)
        } finally { db.close() }
    }

    @Test fun customMetadataSurvivesAudioRescanAndResetKeepsTranscriptAndOriginalFiles() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val audio = createTestWave(app, "Book-actions-${System.nanoTime()}.wav", 2)
        val image = File(File(app.cacheDir, "exports").apply { mkdirs() }, "cover-${System.nanoTime()}.png")
        var copied: File? = null
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val importer = com.example.readalong.data.importing.AudioImporter(app, db)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", audio)
            val id = importer.importFiles(listOf(uri), false).bookIds.single()
            val actions = BookActionsRepository(app, db)
            actions.edit(id, "New title", "New author", android.net.Uri.fromFile(image))
            val custom = db.bookDao().findBook(id)!!
            val filename = android.net.Uri.parse(custom.coverUri).lastPathSegment!!
            copied = File(app.filesDir, "covers/$filename")
            assertTrue(copied.exists())
            importer.importFiles(listOf(uri), false)
            val rescanned = db.bookDao().findBook(id)!!
            assertEquals("New title", rescanned.title); assertEquals("New author", rescanned.author); assertEquals(custom.coverUri, rescanned.coverUri)
            db.bookDao().updateListeningPosition(id, 1500, 42); db.bookDao().setFinished(id, true)
            db.bookDao().putMetadata(LibraryMetadataEntity("file_position:$id", "1500"))
            db.readingDao().putSegments(listOf(TranscriptEntity("s", id, "c", 0, 1000, "A traveler crossed the bridge.", null, null)))
            actions.notStarted(id)
            assertEquals(0L, db.bookDao().progress(id)!!.audioPositionMs)
            assertFalse(db.bookDao().progress(id)!!.isFinished); assertNull(db.bookDao().progress(id)!!.lastPlayedAt)
            assertNull(db.bookDao().metadata("file_position:$id")); assertEquals(1, db.readingDao().count(id))
            actions.delete(id)
            assertNull(db.bookDao().findBook(id)); assertEquals(0, db.readingDao().count(id))
            assertTrue(audio.exists()); assertTrue(image.exists())
        } finally { copied?.delete(); image.delete(); audio.delete(); db.close() }
    }
}
