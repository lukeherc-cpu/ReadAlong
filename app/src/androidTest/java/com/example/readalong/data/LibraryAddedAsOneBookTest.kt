package com.example.readalong.data

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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** A whole library folder added by mistake as one book must never get stuck: Add audiobooks can always bring its books back. */
class LibraryAddedAsOneBookTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
    private val book get() = DocumentsContract.buildTreeDocumentUri(DeletedBookRecoveryTest.AUTHORITY, "book")
    private val library get() = DocumentsContract.buildTreeDocumentUri(DeletedBookRecoveryTest.AUTHORITY, "library")
    private lateinit var db: ReadAlongDatabase

    @Before fun setUp() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        try {
            app.contentResolver.call(book, "grant-original-book", null, null)
            app.contentResolver.call(book, "show-new-book", "true", null)
        } finally { automation.dropShellPermissionIdentity() }
        db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
    }

    @After fun tearDown() {
        app.contentResolver.call(book, "show-new-book", "false", null)
        db.close()
    }

    private suspend fun books() = db.mediaDao().allAudio().map { it.bookId }.distinct()

    @Test fun previewTellsALibraryFromOneBookInDiscs() = runBlocking {
        val importer = AudioImporter(app, db)
        val shelf = importer.preview(library)
        assertEquals(FolderMode.LIBRARY, shelf.suggested)
        assertEquals(setOf("book", "New book"), shelf.books.map { it.name }.toSet())
        assertNull(shelf.emptyMessage)
        // CD/Disc folders straight inside are one book in parts, not a library.
        assertEquals(FolderMode.ONE_BOOK, importer.preview(book).suggested)
        // Looking never adds anything.
        assertTrue(books().isEmpty())
        assertTrue(db.mediaDao().savedFolders().isEmpty())
    }

    @Test fun deletedWholeLibraryBookCanBeAddedAgainAsALibrary() = runBlocking {
        val importer = AudioImporter(app, db)
        val mistake = importer.addFolder(library, FolderMode.ONE_BOOK, false).bookIds.single()
        assertEquals(5, db.mediaDao().audioFiles(mistake).size)
        BookActionsRepository(app, db).delete(mistake)
        // Deleting the book forgets its one-book folder, so it isn't left hidden behind.
        assertNull(db.mediaDao().folder(library.toString()))

        val preview = importer.preview(library)
        assertTrue(preview.deletedAsOneBook)
        assertEquals(2, preview.deletedBooks)
        // A rescan or a plain library add keeps deleted books deleted…
        assertTrue(importer.addFolder(library, FolderMode.LIBRARY, false).bookIds.isEmpty())
        importer.rescanSavedFolders()
        assertTrue(books().isEmpty())
        // …until the person chooses to bring them back.
        val restored = importer.addFolder(library, FolderMode.LIBRARY, false, restoreDeleted = true)
        assertEquals(2, restored.bookIds.size)
        assertEquals(2, books().size)
        assertFalse(mistake in books())
        importer.rescanSavedFolders()
        assertEquals(2, books().size)
    }

    @Test fun addingAOneBookFolderAgainAsALibrarySplitsIt() = runBlocking {
        val importer = AudioImporter(app, db)
        val mistake = importer.addFolder(library, FolderMode.ONE_BOOK, false).bookIds.single()
        val split = importer.addFolder(library, FolderMode.LIBRARY, false)
        assertEquals(2, split.bookIds.size)
        assertNull(db.bookDao().findBook(mistake))
        assertEquals(setOf(4, 1), books().map { db.mediaDao().audioFiles(it).size }.toSet())
        assertEquals(FolderMode.LIBRARY.name, db.mediaDao().folder(library.toString())!!.mode)
        // Nothing was marked deleted on the way: a rescan keeps both books.
        importer.rescanSavedFolders()
        assertEquals(2, books().size)
    }
}
