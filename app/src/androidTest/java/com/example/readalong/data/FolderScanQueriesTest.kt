package com.example.readalong.data

import android.content.Context
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.data.importing.AudioImporter
import com.example.readalong.data.importing.FolderMode
import com.example.readalong.data.local.ReadAlongDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A folder scan asks the storage provider once per folder, not once per file property: each of those
 * is a call to another process, and a large library made tens of thousands of them.
 */
class FolderScanQueriesTest {
    @Test fun rescanAsksTheProviderOncePerFolder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        val authority = "com.example.readalong.test.original-books"
        val book = DocumentsContract.buildTreeDocumentUri(authority, "book")
        val library = DocumentsContract.buildTreeDocumentUri(authority, "library")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        try {
            context.contentResolver.call(book, "grant-original-book", null, null)
            context.contentResolver.call(book, "show-new-book", "false", null)
            fun queries() = context.contentResolver.call(book, "query-count", null, null)!!.getInt("count")
            val importer = AudioImporter(context, db)
            val added = importer.addFolder(library, FolderMode.LIBRARY, false)
            val id = added.bookIds.single()
            assertEquals(4, db.mediaDao().audioFiles(id).size)
            queries()
            val rescan = importer.scan(library)
            val count = queries()
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("scanQueries", count.toString()) })
            assertEquals(listOf(id), rescan.bookIds)
            // library, book, Disc 1 and Disc 2 listed once each, plus the root's own checks.
            assertTrue("A rescan of 3 folders and 4 files made $count provider queries", count <= 10)
        } finally { context.contentResolver.call(book, "show-new-book", "false", null); automation.dropShellPermissionIdentity(); db.close() }
    }
}
