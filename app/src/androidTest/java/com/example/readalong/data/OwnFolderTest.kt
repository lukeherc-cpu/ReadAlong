package com.example.readalong.data

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.data.audible.AudibleLibraryFolder
import com.example.readalong.data.importing.AudioImporter
import com.example.readalong.data.importing.FolderMode
import com.example.readalong.data.importing.OwnFolderProvider
import com.example.readalong.data.local.ReadAlongDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** ReadAlong's own "Audible Audiobooks" folder scans like a chosen folder, with no permission grant, and lives in shared storage. */
class OwnFolderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val shared = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), OwnFolderProvider.NAME)
    private val private = File(context.filesDir, OwnFolderProvider.NAME)

    private fun <T> withImporter(block: suspend (AudioImporter, ReadAlongDatabase) -> T): T = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        try { block(AudioImporter(context, database), database) } finally { database.close() }
    }

    @Test fun ownFolderIsSharedStorageOnAndroid11AndLater() {
        if (Build.VERSION.SDK_INT >= 30) assertEquals(shared.absolutePath, OwnFolderProvider.base(context).absolutePath)
    }

    @Test fun ownFolderAddsAndRescansWithoutAGrant() = withImporter { importer, database ->
        val book = File(OwnFolderProvider.base(context), "Own Folder Test Book").apply { mkdirs() }
        try {
            createTestWave(context, "Own Folder Test Book.wav").copyTo(File(book, "Own Folder Test Book.wav"), overwrite = true)
            val tree = OwnFolderProvider.treeUri(context)
            val id = importer.addFolder(tree, FolderMode.LIBRARY, persistAccess = false).newBookIds.single()
            assertNull(database.mediaDao().folder(tree.toString())?.error)
            val rescan = importer.scan(tree)
            assertTrue(id in rescan.bookIds)
            assertFalse(id in rescan.newBookIds)
        } finally { book.deleteRecursively() }
    }

    @Test fun booksInAppStorageMoveToSharedStorageAndKeepTheirIds() = withImporter { importer, _ ->
        if (Build.VERSION.SDK_INT < 30) return@withImporter
        val old = File(private, "Migrate Test Book").apply { mkdirs() }
        try {
            createTestWave(context, "Migrate Test Book.wav").copyTo(File(old, "Migrate Test Book.wav"), overwrite = true)
            val tree = OwnFolderProvider.treeUri(context)
            // Served from app storage before it moves.
            val id = importer.addFolder(tree, FolderMode.LIBRARY, persistAccess = false).newBookIds.single()
            OwnFolderProvider.migrate(context)
            assertTrue(File(shared, "Migrate Test Book/Migrate Test Book.wav").isFile)
            assertFalse(old.exists())
            val rescan = importer.scan(tree)
            assertTrue(id in rescan.bookIds)
            assertTrue(rescan.newBookIds.isEmpty())
        } finally { old.deleteRecursively(); File(shared, "Migrate Test Book").deleteRecursively() }
    }

    @Test fun aDownloadIsPlacedInTheOwnFolderAndAddedToTheLibrary() = withImporter { importer, database ->
        val download = createTestWave(context, "Place Test Book.wav")
        try {
            val result = AudibleLibraryFolder(context, importer).place("Place Test Book", listOf(download))
            val id = result.newBookIds.single()
            assertTrue(File(OwnFolderProvider.base(context), "Place Test Book/Place Test Book.wav").isFile)
            assertFalse(download.exists())
            assertNotNull(database.bookDao().findBook(id))
        } finally { File(OwnFolderProvider.base(context), "Place Test Book").deleteRecursively() }
    }
}
