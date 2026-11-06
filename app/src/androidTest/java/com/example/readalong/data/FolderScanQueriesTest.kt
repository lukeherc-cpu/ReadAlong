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
            // On the API 34 CI emulator neither the app nor the shell can reach the test provider yet
            // (the other OriginalBooksProvider tests fail the same way), so this skips there instead of failing.
            org.junit.Assume.assumeTrue("The test provider can't be reached", runCatching { shellCall("grant-original-book") }.isSuccess)
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
        val command = "content call --user 0 --uri content://com.example.readalong.test.original-books/tree/book --method $method" + (arg?.let { " --arg $it" } ?: "")
        val streams = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRwe(command)
        streams[1].close()
        val text = listOf(streams[0], streams[2]).joinToString("\n") { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
        check("Result:" in text) { "$command: $text" }
        return text
    }
}
