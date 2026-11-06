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
        try {
            shellCall("grant-original-book")
            shellCall("show-new-book", "false")
            fun queries() = Regex("count=(\\d+)").find(shellCall("query-count"))!!.groupValues[1].toInt()
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
        } finally { db.close() }
    }

    /**
     * Calls the test provider as the shell, which holds MANAGE_DOCUMENTS. The app process can't open the
     * provider itself on API 34 even with the shell's identity adopted; the URI grants it hands out still work.
     */
    private fun shellCall(method: String, arg: String? = null): String {
        val command = "content call --uri content://com.example.readalong.test.original-books --method $method" + (arg?.let { " --arg $it" } ?: "")
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("sh -c '$command 2>&1'")
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().readText()
        check("Result:" in text) { "$command: $text" }
        return text
    }
}
