package com.example.readalong.data

import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.importing.*
import com.example.readalong.data.epub.ImportedEpubRepository
import com.example.readalong.data.local.ReadAlongDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FolderEpubTest {
    @Test fun folderEpubIsImportedOnceAndARescanKeepsTheSelectedEdition() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val authority = "com.example.readalong.test.original-books"
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "book")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val epubs = ImportedEpubRepository(app, db)
        var id: String? = null
        try {
            automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
            app.contentResolver.call(tree, "grant-original-book", null, null)
            app.contentResolver.call(tree, "show-epub", "true", null)
            var imports = 0
            val importer = AudioImporter(app, db, onEpubFound = { book, uri -> imports++; epubs.import(book, uri) })
            id = importer.addFolder(tree, FolderMode.ONE_BOOK, false).bookIds.single()
            val edition = db.importedEpubDao().find(id)!!
            assertEquals("Companion book", edition.title)
            assertTrue(db.importedEpubDao().blocks(id, edition.editionId).any { it.text.contains("before any transcription") })
            assertTrue(importer.matchedEpub(id)); assertEquals(1, imports)
            importer.scan(tree)
            assertEquals(edition.editionId, db.importedEpubDao().find(id)!!.editionId)
            assertEquals(1, imports); assertEquals(0, db.readingDao().count(id))
        } finally {
            app.contentResolver.call(tree, "show-epub", "false", null)
            id?.let { epubs.remove(it) }; automation.dropShellPermissionIdentity(); db.close()
        }
    }
}
