package com.example.readalong.data.audible

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.example.readalong.data.importing.AudioImporter
import com.example.readalong.data.importing.FolderMode
import com.example.readalong.data.importing.ImportResult
import com.example.readalong.data.importing.OwnFolderProvider
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException

/**
 * Puts a downloaded Audible book in a library folder, as "<folder>/<Title>/<Title>.m4b", so it is a
 * folder book like any other: listed on the folders page, kept in sync, with ReadAlongs saved beside it.
 *
 * The folder is the person's own library folder when ReadAlong may write to it. Without one, it is
 * ReadAlong's "Audible Audiobooks" folder, made and added to the library the first time it's needed.
 */
class AudibleLibraryFolder(private val context: Context, private val importer: AudioImporter) {
    private val resolver = context.contentResolver

    /**
     * A book already saved in ReadAlong's own folder by an earlier try whose scan didn't finish, so a
     * retry only scans again instead of asking Audible for another licence and downloading it again.
     */
    fun alreadyPlaced(title: String): List<File> = OwnFolderProvider.locate(context, title).listFiles().orEmpty()
        .filter { it.isFile && it.length() > 0 && it.extension.lowercase() in setOf("m4b", "mp3") }.sortedBy { it.name }

    /** Scans ReadAlong's own folder, adding it to the library first if it isn't there yet. */
    suspend fun scanOwn(): ImportResult {
        val tree = OwnFolderProvider.treeUri(context)
        return if (importer.folders.first().any { it.uri == tree.toString() }) importer.scan(tree)
            else importer.addFolder(tree, FolderMode.LIBRARY, persistAccess = false)
    }

    /** Copies [files] (a finished download, in order) into the book's folder, scans the library folder, then deletes the download. */
    suspend fun place(title: String, files: List<File>): ImportResult {
        val writable = resolver.persistedUriPermissions.filter { it.isWritePermission }.map { it.uri.toString() }.toSet()
        val chosen = importer.folders.first().firstOrNull { it.mode == FolderMode.LIBRARY.name && it.uri in writable }
        // ReadAlong's own folder goes through its provider too, which keeps names unique and the folder in one place.
        val tree = chosen?.let { Uri.parse(it.uri) } ?: OwnFolderProvider.treeUri(context)
        copyInto(tree, title, files)
        val result = if (chosen != null) importer.scan(tree) else scanOwn()
        files.forEach { it.delete() }
        return result
    }

    /** Copies into a folder the person chose. A copy cut short is deleted, so a scan never finds half a book. */
    private fun copyInto(tree: Uri, title: String, files: List<File>) {
        val top = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val book = child(tree, top, title)?.takeIf { it.second }?.first
            ?: DocumentsContract.createDocument(resolver, top, Document.MIME_TYPE_DIR, title)
            ?: throw IOException("Couldn't make a folder for $title in your library folder.")
        files.forEach { file ->
            val existing = child(tree, book, file.name)
            if (existing != null && !existing.second) {
                if (existing.third == file.length()) return@forEach
                DocumentsContract.deleteDocument(resolver, existing.first) // Left by a copy that didn't finish.
            }
            val target = DocumentsContract.createDocument(resolver, book, anyFile, file.name)
                ?: throw IOException("Couldn't save ${file.name} in your library folder.")
            try {
                (resolver.openOutputStream(target, "w") ?: throw IOException("Couldn't write ${file.name}.")).use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }
            } catch (failure: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, target) }
                throw failure
            }
        }
    }

    /** The child of [parent] named [name]: its uri, whether it's a folder, and its size. */
    private fun child(tree: Uri, parent: Uri, name: String): Triple<Uri, Boolean, Long>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == name)
                return Triple(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)), cursor.getString(2) == Document.MIME_TYPE_DIR, if (cursor.isNull(3)) -1 else cursor.getLong(3))
        }
        return null
    }

    /** Some providers add the extension they think a type should have ("Book.m4b.mp4"); this type keeps the name as given. */
    private val anyFile = "application/octet-stream"
}
