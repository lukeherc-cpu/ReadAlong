package com.example.readalong.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.local.*
import com.example.readalong.data.importing.AudioImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FolderRemovalTest {
    @Test fun removalDeletesOnlyItsLibraryBooksCheckpointsAndDerivedAssets() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val id = "folder-removal-${System.nanoTime()}"
        val folder = FolderEntity("content://fixture/library/$id", "Test library", "LIBRARY", 0, null)
        val prefix = com.example.readalong.domain.epub.epubFragment(id)
        val derived = File(File(app.filesDir, "editions").apply { mkdirs() }, "$prefix-fixture.epub")
        val original = File(app.cacheDir, "$id.wav")
        try {
            derived.writeText("derived"); original.writeText("original audio")
            val book = com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false)
            db.bookDao().upsertBooks(listOf(book, book.copy(id = "$id-other")))
            db.mediaDao().putFolder(folder)
            db.mediaDao().putAudio(listOf(AudioFileEntity("$id-file", id, original.toURI().toString(), "Book", 0, 30_000, folder.uri)))
            db.bookDao().insertProgress(listOf(BookProgressEntity(id, audioPositionMs = 12_000)))
            db.processingDao().put(ProcessingJobEntity(id, "work", "whistle-v1", "CANCELLED", 1, 4, "Saved", 0))
            db.readingDao().putSegments(listOf(TranscriptEntity("$id-sentence", id, "chapter", 0, 1000, "Saved text", null, null)))
            db.bookDao().putMetadata(LibraryMetadataEntity("paused_notification:$id", "true"))
            AudioImporter(app, db).removeFolder(folder)
            assertNull(db.bookDao().findBook(id)); assertNull(db.mediaDao().folder(folder.uri))
            assertNull(db.processingDao().find(id)); assertEquals(0, db.readingDao().count(id))
            assertNull(db.bookDao().progress(id)); assertNull(db.bookDao().metadata("paused_notification:$id"))
            assertFalse(derived.exists()); assertTrue(original.exists()); assertNotNull(db.bookDao().findBook("$id-other"))
        } finally { derived.delete(); original.delete(); db.close() }
    }
}
