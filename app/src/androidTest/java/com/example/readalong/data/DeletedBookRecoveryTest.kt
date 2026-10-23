package com.example.readalong.data

import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.book.BookActionsRepository
import com.example.readalong.data.importing.AudioImporter
import com.example.readalong.data.importing.FolderMode
import com.example.readalong.data.local.ReadAlongDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeletedBookRecoveryTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
    private val book get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "book")
    private val library get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "library")
    private fun grant() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        try { app.contentResolver.call(book, "grant-original-book", null, null) }
        finally { automation.dropShellPermissionIdentity() }
    }

    @Test fun deleteSurvivesDatabaseReopenAndOverlappingFolderRescansButExplicitAddRestores() = runBlocking {
        grant()
        val name = "delete-restart-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(app, ReadAlongDatabase::class.java, name).build()
        try {
            app.contentResolver.call(book, "show-new-book", "true", null)
            var importer = AudioImporter(app, db)
            val id = importer.addFolder(book, FolderMode.ONE_BOOK, false).bookIds.single()
            importer.addFolder(library, FolderMode.LIBRARY, false)
            val other = db.mediaDao().allAudio().map { it.bookId }.distinct().single { it != id }
            db.bookDao().updateListeningPosition(other, 987, 42)
            val sources = db.mediaDao().audioFiles(id).map { Uri.parse(it.uri) }
            BookActionsRepository(app, db).delete(id)
            assertNull(db.bookDao().findBook(id))
            repeat(2) { importer.rescanSavedFolders() }
            assertNull(db.bookDao().findBook(id))
            db.close()
            db = Room.databaseBuilder(app, ReadAlongDatabase::class.java, name).build()
            importer = AudioImporter(app, db)
            importer.rescanSavedFolders()
            assertNull(db.bookDao().findBook(id))
            assertEquals(listOf(other), db.mediaDao().allAudio().map { it.bookId }.distinct())
            assertEquals(987L, db.bookDao().progress(other)!!.audioPositionMs)
            sources.forEach { app.contentResolver.openInputStream(it)!!.use { stream -> assertTrue(stream.read() >= 0) } }
            val restored = importer.addFolder(book, FolderMode.ONE_BOOK, false)
            assertEquals(listOf(id), restored.newBookIds)
            assertEquals(4, db.mediaDao().audioFiles(id).size)
            importer.rescanSavedFolders()
            assertEquals(2, db.mediaDao().allAudio().map { it.bookId }.distinct().size)
        } finally {
            app.contentResolver.call(book, "show-new-book", "false", null)
            db.close(); app.deleteDatabase(name)
        }
    }

    @Test fun deletingDirectDocumentAlsoExcludesItsTreeAliasUntilExplicitFileImport() = runBlocking {
        grant()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        try {
            val importer = AudioImporter(app, db)
            val direct = DocumentsContract.buildDocumentUri(AUTHORITY, "book/Disc 1/Part 2.wav")
            val id = importer.importFiles(listOf(direct), false).bookIds.single()
            BookActionsRepository(app, db).delete(id)
            assertTrue(importer.addFolder(library, FolderMode.LIBRARY, false).bookIds.isEmpty())
            assertTrue(db.mediaDao().allAudio().isEmpty())
            importer.importFiles(listOf(direct), false)
            importer.rescanSavedFolders()
            assertEquals(1, db.mediaDao().allAudio().map { it.bookId }.distinct().size)
            assertEquals(4, db.mediaDao().audioFiles(id).size)
        } finally { db.close(); automation.dropShellPermissionIdentity() }
    }
    companion object { const val AUTHORITY = "com.example.readalong.test.original-books" }
}
