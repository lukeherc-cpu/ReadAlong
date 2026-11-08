package com.example.readalong.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.data.importing.AudioImporter
import com.example.readalong.data.importing.FolderMode
import com.example.readalong.data.importing.OwnFolderProvider
import com.example.readalong.data.local.ReadAlongDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** ReadAlong's own "Audible Audiobooks" folder scans like a chosen folder, with no permission grant. */
class OwnFolderTest {
    @Test fun ownFolderAddsAndRescansWithoutAGrant() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        val base = OwnFolderProvider.base(context)
        val book = File(base, "Own Folder Test Book").apply { mkdirs() }
        try {
            createTestWave(context, "Own Folder Test Book.wav").copyTo(File(book, "Own Folder Test Book.wav"), overwrite = true)
            val importer = AudioImporter(context, database)
            val tree = OwnFolderProvider.treeUri(context)
            val added = importer.addFolder(tree, FolderMode.LIBRARY, persistAccess = false)
            val id = added.newBookIds.single()
            assertNull(database.mediaDao().folder(tree.toString())?.error)
            val rescan = importer.scan(tree)
            assertTrue(id in rescan.bookIds)
            assertFalse(id in rescan.newBookIds)
        } finally {
            book.deleteRecursively()
            database.close()
        }
    }
}
