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
            val id = AudibleLibraryFolder(context, importer, database).place("Place Test Book", listOf(download)).single()
            assertTrue(File(OwnFolderProvider.base(context), "Place Test Book/Place Test Book.wav").isFile)
            assertFalse(download.exists())
            assertNotNull(database.bookDao().findBook(id))
            assertTrue(id in database.mediaDao().folderBooks(OwnFolderProvider.treeUri(context).toString()))
        } finally { File(OwnFolderProvider.base(context), "Place Test Book").deleteRecursively() }
    }

    @Test fun downloadingABookAgainAfterDeletingItBringsItBack() = withImporter { importer, database ->
        val folder = AudibleLibraryFolder(context, importer, database)
        try {
            val first = folder.place("Deleted Test Book", listOf(createTestWave(context, "Deleted Test Book.wav"))).single()
            com.example.readalong.data.book.BookActionsRepository(context, database).delete(first)
            assertNull(database.bookDao().findBook(first))
            // The same file name in the same folder: without restoring it, the scan would skip it as deleted.
            val again = folder.place("Deleted Test Book", listOf(createTestWave(context, "Deleted Test Book.wav"))).single()
            assertNotNull(database.bookDao().findBook(again))
        } finally { File(OwnFolderProvider.base(context), "Deleted Test Book").deleteRecursively() }
    }

    /** Installed over a ReadAlong from before 0.49.0: its Audible books were loose files in app storage. */
    @Test fun anOldDownloadMovesIntoTheFolderAndKeepsItsBookAndProgress() = withImporter { importer, database ->
        val asin = "B0UPGRADETEST"
        val store = com.example.readalong.data.audible.AudibleStore.get(context)
        val old = store.folder(asin).apply { mkdirs() }
        try {
            val file = File(old, "Upgrade Test Book.mp3")
            createTestWave(context, "Upgrade Test Book.wav").copyTo(file, overwrite = true)
            File(old, ".imported").writeText("")
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val id = importer.importFiles(listOf(uri), persistAccess = false).bookIds.single()
            database.bookDao().updateListeningPosition(id, 1234, 1)
            assertEquals(listOf(asin), store.oldDownloads())

            assertEquals(listOf(asin), AudibleLibraryFolder(context, importer, database).adoptOldDownloads(store))
            assertFalse(file.exists())
            val title = database.bookDao().findBook(id)!!.title
            assertTrue(File(OwnFolderProvider.base(context), "${com.example.readalong.audible.AudibleDownloader.safeName(title)}/Upgrade Test Book.mp3").isFile)
            assertTrue(id in database.mediaDao().folderBooks(OwnFolderProvider.treeUri(context).toString()))
            assertEquals(1234L, database.bookDao().progress(id)?.audioPositionMs)
            assertTrue(store.oldDownloads().isEmpty())
            // Nothing left to move the next time.
            assertTrue(AudibleLibraryFolder(context, importer, database).adoptOldDownloads(store).isEmpty())
        } finally {
            old.deleteRecursively()
            OwnFolderProvider.base(context).listFiles().orEmpty().filter { it.name.startsWith("Upgrade Test Book") }.forEach { it.deleteRecursively() }
        }
    }
}
