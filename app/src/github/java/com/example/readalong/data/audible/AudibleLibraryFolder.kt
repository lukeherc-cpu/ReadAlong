package com.example.readalong.data.audible

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.content.FileProvider
import com.example.readalong.ReadAlongApplication
import com.example.readalong.audible.AudibleDownloader
import com.example.readalong.data.importing.AudioImporter
import com.example.readalong.data.importing.FolderMode
import com.example.readalong.data.importing.LibraryExclusions
import com.example.readalong.data.importing.OwnFolderProvider
import com.example.readalong.data.local.ReadAlongDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/**
 * Puts a downloaded Audible book in a library folder, as "<folder>/<Title>/<Title>.m4b", so it is a
 * folder book like any other: listed on the folders page, kept in sync, with ReadAlongs saved beside it.
 *
 * The folder is the person's own library folder when ReadAlong may write to it. Without one, it is
 * ReadAlong's "Audible Audiobooks" folder, made and added to the library the first time it's needed.
 *
 * The book is always found by its file after the scan, never by title, and a book the person deleted
 * earlier comes back: downloading it again is asking for it. A download that still isn't in the
 * library afterwards is an error, never "In your library".
 */
class AudibleLibraryFolder(private val context: Context, private val importer: AudioImporter,
    private val database: ReadAlongDatabase = (context.applicationContext as ReadAlongApplication).database) {
    private val resolver = context.contentResolver
    private val ownTree get() = OwnFolderProvider.treeUri(context)

    /**
     * A book already saved in ReadAlong's own folder by an earlier try whose scan didn't finish, so a
     * retry only scans again instead of asking Audible for another licence and downloading it again.
     */
    fun alreadyPlaced(title: String): List<File> = OwnFolderProvider.locate(context, title).listFiles().orEmpty()
        .filter { it.isFile && it.length() > 0 && it.extension.lowercase() in AUDIO }.sortedBy { it.name }

    /** Scans ReadAlong's own folder (adding it to the library first if it isn't there), bringing back [restore] if deleted. */
    private suspend fun scanOwn(restore: Set<String>) =
        if (importer.folders.first().any { it.uri == ownTree.toString() }) importer.scan(ownTree, restore)
        else importer.addFolder(ownTree, FolderMode.LIBRARY, persistAccess = false, restore = restore)

    /** Adds a book [alreadyPlaced] in the own folder to the library. Returns its book ids, as [place] does. */
    suspend fun addPlaced(title: String): List<String> {
        val placed = alreadyPlaced(title)
        scanOwn(setOf(title))
        val top = DocumentsContract.buildDocumentUriUsingTree(ownTree, DocumentsContract.getTreeDocumentId(ownTree))
        val folder = child(ownTree, top, title)?.first ?: throw IOException("Couldn't find $title in ${OwnFolderProvider.NAME}.")
        return books(placed.mapNotNull { child(ownTree, folder, it.name)?.first }, title)
    }

    /**
     * Copies [files] (a finished download, in order) into the book's folder, scans that library folder
     * and deletes the download. Returns the book's id (one book; a list so callers can queue it).
     */
    suspend fun place(title: String, files: List<File>): List<String> {
        val writable = resolver.persistedUriPermissions.filter { it.isWritePermission }.map { it.uri.toString() }.toSet()
        val chosen = importer.folders.first().firstOrNull { it.mode == FolderMode.LIBRARY.name && it.uri in writable }?.let { Uri.parse(it.uri) }
        // ReadAlong's own folder goes through its provider too, which keeps names unique and the folder in one place.
        // A chosen folder that can't take the book (full, removed SD card, access withdrawn) falls back to the own folder.
        val inChosen = chosen?.let { tree -> runCatching { copyInto(tree, title, files) }.getOrNull() }
        val saved = inChosen ?: copyInto(ownTree, title, files)
        if (inChosen != null) importer.scan(chosen!!, setOf(saved.folder)) else scanOwn(setOf(saved.folder))
        val ids = books(saved.files, title)
        files.forEach { it.delete() }
        return ids
    }

    /** The library book that holds [files] after a scan; an error if the scan didn't add it. */
    private suspend fun books(files: List<Uri>, title: String): List<String> {
        val sources = files.map { LibraryExclusions.source(it) }.toSet()
        val ids = database.mediaDao().allAudio().filter { LibraryExclusions.source(Uri.parse(it.uri)) in sources }.map { it.bookId }.distinct()
            .filter { database.bookDao().findBook(it) != null }
        if (ids.isEmpty()) throw IOException("$title downloaded, but ReadAlong couldn't add it to your library. Pull down on Library folders to check again.")
        return ids
    }

    private class Saved(val folder: String, val files: List<Uri>)

    /** Copies into a library folder. A copy cut short is deleted, so a scan never finds half a book. */
    private fun copyInto(tree: Uri, title: String, files: List<File>): Saved {
        val top = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val existingFolder = child(tree, top, title)?.takeIf { it.second }?.first
        val book = existingFolder ?: DocumentsContract.createDocument(resolver, top, Document.MIME_TYPE_DIR, title)
            ?: throw IOException("Couldn't make a folder for $title in your library folder.")
        // The provider may have given the folder another name ("Title (1)") when the name was taken.
        val folderName = name(book) ?: title
        val saved = files.map { file ->
            val existing = child(tree, book, file.name)
            if (existing != null && !existing.second) {
                if (existing.third == file.length()) return@map existing.first
                DocumentsContract.deleteDocument(resolver, existing.first) // Left by a copy that didn't finish.
            }
            val target = DocumentsContract.createDocument(resolver, book, ANY_FILE, file.name)
                ?: throw IOException("Couldn't save ${file.name} in your library folder.")
            try {
                (resolver.openOutputStream(target, "w") ?: throw IOException("Couldn't write ${file.name}.")).use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }
            } catch (failure: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, target) }
                throw failure
            }
            target
        }
        return Saved(folderName, saved)
    }

    private fun name(document: Uri): String? = runCatching {
        resolver.query(document, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** The child of [parent] named [name]: its uri, whether it's a folder, and its size. */
    private fun child(tree: Uri, parent: Uri, name: String): Triple<Uri, Boolean, Long>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == name)
                return Triple(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)), cursor.getString(2) == Document.MIME_TYPE_DIR, if (cursor.isNull(3)) -1 else cursor.getLong(3))
        }
        return null
    }

    /**
     * Books downloaded before 0.49.0 were added as loose files from ReadAlong's private storage, so they
     * sat in the library without a folder. Each moves into the own folder and keeps its book: its audio
     * rows point at the new copy before the scan, so the scan finds a book it already has, with the same
     * id, progress, chapters and ReadAlong. Returns the ASINs it moved. A book deleted from the library
     * isn't brought back; its download is left for Download to reuse instead of fetching it again.
     */
    suspend fun adoptOldDownloads(store: AudibleStore): List<String> = adopting.withLock {
        val moved = mutableListOf<String>()
        val audio = database.mediaDao()
        store.oldDownloads().forEach { asin ->
            // One book that can't move (no space, unreadable) never stops the others; it stays where it is and keeps playing.
            try {
                val home = store.folder(asin)
                val files = home.listFiles().orEmpty().filter { it.isFile && it.length() > 0 && it.extension.lowercase() in AUDIO }.sortedBy { it.name }
                if (files.isEmpty()) return@forEach
                val rows = files.map { file -> audio.audioFile(FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString()) }
                val bookId = rows.firstOrNull()?.bookId
                if (bookId == null || rows.any { it?.bookId != bookId } || database.bookDao().findBook(bookId) == null) {
                    store.forget(asin) // Deleted from the library: Download shows again and reuses the file.
                    return@forEach
                }
                val title = AudibleDownloader.safeName(store.book(asin)?.title ?: database.bookDao().findBook(bookId)?.title ?: files.first().nameWithoutExtension)
                val saved = copyInto(ownTree, title, files)
                audio.putAudio(rows.zip(saved.files) { row, uri -> row!!.copy(uri = uri.toString(), folderUri = ownTree.toString()) })
                scanOwn(setOf(saved.folder))
                books(saved.files, title)
                files.forEach { it.delete() }
                store.finished(asin, listOf(bookId))
                moved += asin
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
        moved
    }

    companion object {
        private val AUDIO = setOf("m4b", "mp3")
        /** The Audible screen and a download can both start adopting: one at a time, so nothing is copied twice. */
        private val adopting = kotlinx.coroutines.sync.Mutex()
        /** Some providers add the extension they think a type should have ("Book.m4b.mp4"); this type keeps the name as given. */
        private const val ANY_FILE = "application/octet-stream"
    }
}
