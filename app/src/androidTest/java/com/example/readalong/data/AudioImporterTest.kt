package com.example.readalong.data

import android.content.Context
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.data.importing.AudioImporter
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.data.book.RoomBookRepository
import com.example.readalong.data.reading.toEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real, locally generated PCM audio; no user files or licensed recordings. */
fun createTestWave(context: Context, name: String, seconds: Int = 2): File {
    val samples = 16_000 * seconds
    val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(samples * 2)
    repeat(samples) { index -> buffer.putShort((kotlin.math.sin(index * 2 * Math.PI * 440 / 16_000) * 500).toInt().toShort()) }
    return File(File(context.cacheDir, "exports").apply { mkdirs() }, name).apply { writeBytes(buffer.array()) }
}

class AudioImporterTest {
    @Test fun oneFolderImportsNestedDiscsAsOneBookInNaturalPathOrder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        try {
            val tree = android.provider.DocumentsContract.buildTreeDocumentUri("com.example.readalong.test.original-books", "book")
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
            try { context.contentResolver.call(tree, "grant-original-book", null, null) }
            finally { automation.dropShellPermissionIdentity() }
            val importer = AudioImporter(context, database)
            val result = importer.addFolder(tree, com.example.readalong.data.importing.FolderMode.ONE_BOOK, false)
            val id = result.bookIds.single()
            assertEquals(listOf(id), result.newBookIds)
            val audio = database.mediaDao().audioFiles(id)
            assertEquals(4, audio.size)
            assertEquals(listOf("book/Disc 1/Part 2.wav", "book/Disc 1/Part 10.wav", "book/Disc 2/Part 1.wav", "book/Disc 2/Part 2.wav"),
                audio.map { android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(it.uri)) })
            assertTrue(database.bookDao().findBook(id)!!.durationMs!! in 7900..8100)
            database.bookDao().updateListeningPosition(id, 3200, 1)
            val rescan = importer.scan(tree)
            assertEquals(listOf(id), rescan.bookIds)
            assertTrue(rescan.newBookIds.isEmpty())
            assertEquals(3200L, database.bookDao().progress(id)?.audioPositionMs)
        } finally { database.close() }
    }

    @Test fun multiFileImportSortsChaptersKeepsProgressAndAvoidsDuplicates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        val files = listOf(createTestWave(context, "Chapter 10.wav"), createTestWave(context, "Chapter 2.wav"))
        try {
            val importer = AudioImporter(context, database)
            val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
            val result = importer.importFiles(uris, persistAccess = false)
            val id = result.bookIds.single()
            assertEquals(listOf(id), result.newBookIds)
            val chapters = database.mediaDao().audioFiles(id)
            assertEquals(listOf("Chapter 2.wav", "Chapter 10.wav"), chapters.map { it.name })
            assertEquals(2, chapters.size)
            val repository = RoomBookRepository(database, seedSamples = true)
            val book = requireNotNull(repository.observeBook(id).first())
            assertFalse(book.isDemo)
            assertEquals("AVAILABLE", book.mediaState)
            assertTrue(book.durationMs!! >= 3_900)
            repository.updateListeningPosition(id, 1_000)
            val repeated = importer.importFiles(uris, persistAccess = false)
            assertEquals(id, repeated.bookIds.single())
            assertTrue(repeated.newBookIds.isEmpty())
            assertEquals(1_000L, repository.observeBook(id).first()?.positionMs)
            assertEquals(7, repository.observeBooks().first().size)
        } finally { database.close(); files.forEach { it.delete() } }
    }
    @Test fun directFileThenBookThenLibraryReuseIdsAndAutomaticRescanAddsOnlyNewContent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        val authority = "com.example.readalong.test.original-books"
        val book = android.provider.DocumentsContract.buildTreeDocumentUri(authority, "book")
        val library = android.provider.DocumentsContract.buildTreeDocumentUri(authority, "library")
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        try { context.contentResolver.call(book, "grant-original-book", null, null) } finally { automation.dropShellPermissionIdentity() }
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        try {
            context.contentResolver.call(book, "show-new-book", "false", null)
            val importer = AudioImporter(context, db)
            val direct = android.provider.DocumentsContract.buildDocumentUri(authority, "book/Disc 1/Part 2.wav")
            val id = importer.importFiles(listOf(direct), false).bookIds.single()
            val original = db.mediaDao().audioFiles(id).single()
            db.bookDao().updateListeningPosition(id, 1200, 1)
            db.readingDao().putSegments(listOf(com.example.readalong.domain.model.TranscriptSegment("dedup-saved", id, original.id, 0, 1000, "Saved timing.").toEntity()))
            val multipart = importer.addFolder(book, com.example.readalong.data.importing.FolderMode.ONE_BOOK, false)
            assertEquals(listOf(id), multipart.bookIds); assertTrue(multipart.newBookIds.isEmpty())
            assertEquals(4, db.mediaDao().audioFiles(id).size)
            assertEquals(original.id, db.mediaDao().audioFiles(id).first().id)
            val collection = importer.addFolder(library, com.example.readalong.data.importing.FolderMode.LIBRARY, false)
            assertEquals(listOf(id), collection.bookIds); assertTrue(collection.newBookIds.isEmpty())
            assertEquals(1200L, db.bookDao().progress(id)!!.audioPositionMs)
            assertEquals(1, db.readingDao().segments(id).size)
            context.contentResolver.call(book, "show-new-book", "true", null)
            importer.rescanSavedFolders()
            val count = db.mediaDao().allAudio().map { it.bookId }.distinct().size
            assertEquals(2, count)
            importer.rescanSavedFolders()
            assertEquals(count, db.mediaDao().allAudio().map { it.bookId }.distinct().size)
            assertEquals(1, db.readingDao().segments(id).size)
        } finally { context.contentResolver.call(book, "show-new-book", "false", null); automation.dropShellPermissionIdentity(); db.close() }
    }

    @Test fun m4bImportReadsEmbeddedChaptersThroughAndroidContentUri() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, "Chapters-${System.nanoTime()}.m4b")
        try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("audio/chapters.m4b").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val id = AudioImporter(context, database).importFiles(listOf(uri), false).bookIds.single()
            val chapters = database.mediaDao().chapters(id)
            assertEquals(listOf("Opening", "Second chapter"), chapters.map { it.title })
            assertEquals(listOf(0L, 2000L), chapters.map { it.startMs })
            assertEquals(2000L, chapters.first().endMs)
            assertEquals(1, database.mediaDao().audioFiles(id).size)
            assertEquals("AVAILABLE", database.bookDao().findBook(id)?.mediaState)
        } finally { database.close(); file.delete() }
    }

}
