package com.example.readalong.data

import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.importing.*
import com.example.readalong.data.epub.*
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.data.processing.ProcessingRepository
import com.example.readalong.data.transcription.DesktopTranscriptImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompanionTranscriptTest {
    @Test fun folderAudioEpubAndReorderedTranscriptImportTogetherWithoutPhoneRecognition() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val tree = DocumentsContract.buildTreeDocumentUri("com.example.readalong.test.original-books", "book")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val epubs = ImportedEpubRepository(app, db)
        val processing = ProcessingRepository(app, db, app.speechModel, launchPreparation = { error("Companion import must not run recognition") })
        val transcript = DesktopTranscriptImporter(app, db, processing, GeneratedEpubRepository(app, db))
        var id: String? = null
        var imports = 0
        try {
            automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
            app.contentResolver.call(tree, "grant-original-book", null, null)
            app.contentResolver.call(tree, "show-epub", "true", null)
            app.contentResolver.call(tree, "show-transcript", "true", null)
            val importer = AudioImporter(app, db, onEpubFound = { book, uri -> epubs.import(book, uri) }, onTranscriptFound = { book, uri -> imports++; transcript.import(book, uri) })
            id = importer.addFolder(tree, FolderMode.ONE_BOOK, false).bookIds.single()
            assertEquals("READY", db.processingDao().find(id)!!.state)
            assertNotNull(db.importedEpubDao().find(id))
            val rows = db.readingDao().segments(id)
            assertEquals(4, rows.size)
            assertEquals(listOf(0L, 2000L, 4000L, 6000L), rows.sortedBy { it.startMs }.map { it.startMs })
            assertTrue(epubs.align(id))
            // The tone fixture has repeated short text; publication, rather than an
            // invented semantic match, proves the imported transcript reaches alignment.
            assertEquals(db.readingDao().findVersion(id)!!.transcriptRevision, db.importedEpubDao().find(id)!!.alignedRevision)
            assertTrue(db.importedEpubDao().find(id)!!.state !in setOf("QUEUED", "ALIGNING"))
            val edition = db.importedEpubDao().find(id)!!
            val block = db.importedEpubDao().blocks(id, edition.editionId).first()
            val draft = epubs.manualDraft(id, edition.editionId, block.ordinal, 0)
            epubs.saveManual(draft, 0, 1800)
            val pack = DocumentsContract.buildDocumentUriUsingTree(tree, "book/Companion.readalong")
            transcript.import(id, pack)
            assertEquals("QUEUED", db.importedEpubDao().find(id)!!.state)
            assertEquals(1, db.importedEpubDao().find(id)!!.manualCount)
            assertEquals(0L, db.importedEpubDao().blocks(id, edition.editionId).first().manualStartMs)
            assertTrue(epubs.align(id))
            val revision = db.readingDao().findVersion(id)!!.transcriptRevision
            importer.scan(tree)
            assertEquals(1, imports)
            assertEquals(revision, db.readingDao().findVersion(id)!!.transcriptRevision)
        } finally {
            app.contentResolver.call(tree, "show-transcript", "false", null)
            app.contentResolver.call(tree, "show-epub", "false", null)
            id?.let { epubs.remove(it); processing.cancel(it) }; automation.dropShellPermissionIdentity(); db.close()
        }
    }
}
