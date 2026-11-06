package com.example.readalong.data.importing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.documentfile.provider.DocumentFile
import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.domain.link.BookInfo
import com.example.readalong.domain.link.BookInfoFiles
import com.example.readalong.domain.link.BookStateFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * A book's chosen cover and EPUB, kept beside its audio so they travel with the folder (a linked computer,
 * OneDrive, Syncthing, a cable) the way `.readalong` and `.readalong-progress` do, under the same base name:
 * `<book>.cover.jpg` and `<book>.epub`. (Progress, bookmarks and edited titles share `<book>.readalong-state`.) ReadAlong Desktop reads and writes the same files.
 *
 * Each scan adopts a companion file whose size or time changed since this phone last wrote or took it, so a
 * cover or EPUB chosen on the computer replaces the phone's, and the other way round. Folders the phone can
 * only read are left as they are.
 */
class BookFolderFiles(private val context: Context, private val database: ReadAlongDatabase) {
    private val resolver = context.contentResolver
    private val dao = database.bookDao()

    /** Saves [image] (a JPEG) as the book's companion cover. Returns false where the book has no writable folder. */
    suspend fun saveCover(bookId: String, image: File): Boolean = withContext(Dispatchers.IO) {
        val target = write(bookId, COVER) { image.inputStream() } ?: return@withContext false
        signature(target)?.let { dao.putMetadata(LibraryMetadataEntity(COVER_SEEN + bookId, it)) }
        true
    }

    /** Copies an EPUB chosen for the book beside it, unless [source] already is the folder's EPUB. */
    suspend fun saveEpub(bookId: String, source: Uri): Boolean = withContext(Dispatchers.IO) {
        if (dao.metadata(FOLDER_EPUB + bookId) == source.toString()) return@withContext false
        val target = write(bookId, EPUB) { requireNotNull(resolver.openInputStream(source)) } ?: return@withContext false
        dao.putMetadata(LibraryMetadataEntity(FOLDER_EPUB + bookId, target.toString()))
        signature(target)?.let { dao.putMetadata(LibraryMetadataEntity(EPUB_SEEN + bookId, it)) }
        true
    }

    /** A scan found [cover] under the companion name: take it as the book's cover when it changed elsewhere. */
    suspend fun coverFound(bookId: String, cover: Uri) = withContext(Dispatchers.IO) {
        val seen = signature(cover) ?: return@withContext
        if (dao.metadata(COVER_SEEN + bookId) == seen) return@withContext
        com.example.readalong.data.book.BookActionsRepository(context, database).edit(bookId, cover = cover, toFolder = false)
        dao.putMetadata(LibraryMetadataEntity(COVER_SEEN + bookId, seen))
    }

    /**
     * Whether the folder's companion EPUB is new to this phone and should replace the book's EPUB. One that was
     * already the book's EPUB before this phone kept track ([previous], the EPUB the last scan used) is only
     * recorded, so an upgrade never redoes matching.
     */
    suspend fun epubChanged(bookId: String, epub: Uri, hasEdition: Boolean, previous: String?): Boolean = withContext(Dispatchers.IO) {
        val seen = signature(epub) ?: return@withContext false
        val last = dao.metadata(EPUB_SEEN + bookId)
        if (last == seen) return@withContext false
        dao.putMetadata(LibraryMetadataEntity(EPUB_SEEN + bookId, seen))
        last != null || !hasEdition || previous != epub.toString()
    }

    /**
     * Shares a title or author edited on either device through the "info" part of "<book>.readalong-state" (see
     * [FolderBookState]): the newer edit wins. Run by each
     * folder scan, and after an edit here. Returns true when the folder's edit was taken.
     */
    suspend fun syncInfo(bookId: String): Boolean = withContext(Dispatchers.IO) {
        val location = FolderProgressLocation.decode(dao.metadata(PROGRESS + bookId)) ?: return@withContext false
        val book = dao.findBook(bookId) ?: return@withContext false
        val edited = dao.metadata("override:$bookId:title") != null || dao.metadata("override:$bookId:author") != null
        val local = if (edited) BookInfo(book.title, book.author, dao.metadata(EDITED + bookId)?.toLongOrNull() ?: 0) else null
        val state = FolderBookState(context)
        val file = state.read(location, BookStateFiles.Part.INFO)?.let(BookInfoFiles::parse)
        when (BookInfoFiles.direction(local, file)) {
            BookInfoFiles.Action.TAKE -> {
                val taken = requireNotNull(file)
                com.example.readalong.data.book.BookActionsRepository(context, database).edit(bookId, title = taken.title, author = taken.author.orEmpty(), toFolder = false)
                dao.putMetadata(LibraryMetadataEntity(EDITED + bookId, taken.editedAt.toString()))
                true
            }
            BookInfoFiles.Action.WRITE -> {
                state.write(location, BookStateFiles.Part.INFO, BookInfoFiles.encode(requireNotNull(local), "Android"))
                false
            }
            BookInfoFiles.Action.NONE -> false
        }
    }

    private suspend fun write(bookId: String, suffix: String, open: () -> InputStream): Uri? {
        val location = FolderProgressLocation.decode(dao.metadata(PROGRESS + bookId)) ?: return null
        val folder = Uri.parse(location.folder)
        val name = baseName(location) + suffix
        return try {
            val mime = when (suffix) { EPUB -> "application/epub+zip"; COVER -> "image/jpeg"; else -> "application/octet-stream" }
            val target = child(folder, name) ?: DocumentsContract.createDocument(resolver, folder, mime, name) ?: return null
            val output = try { resolver.openOutputStream(target, "wt") } catch (_: IllegalArgumentException) { resolver.openOutputStream(target, "w") }
            open().use { input -> requireNotNull(output).use { input.copyTo(it, 256 * 1024) } }
            target
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            // Folders added before write access was asked for, or a provider that refuses: the copy in the app still works.
            android.util.Log.i("ReadAlong", "$name not saved beside the book: ${failure.javaClass.simpleName}")
            null
        }
    }

    private fun child(folder: Uri, name: String): Uri? = runCatching {
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder)),
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1).equals(name, ignoreCase = true))
                return@use DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0))
            null
        }
    }.getOrNull()

    private fun signature(uri: Uri): String? = runCatching {
        DocumentFile.fromSingleUri(context, uri)?.takeIf { it.exists() }?.let { "${it.length()}:${it.lastModified()}" }
    }.getOrNull()

    companion object {
        const val COVER = ".cover.jpg"
        const val EPUB = ".epub"
        private const val PROGRESS = "folder_progress:"
        private const val FOLDER_EPUB = "folder_epub:"
        private const val COVER_SEEN = "folder_cover_seen:"
        private const val EPUB_SEEN = "folder_epub_seen:"
        /** When the title or author was last edited here (or taken from the other device). */
        const val EDITED = "override_edited_at:"

        /** The base name every companion file of a book shares: its folder's name, or a single-file book's own name. */
        fun baseName(location: FolderProgressLocation) = location.name.removeSuffix(FolderProgress.EXTENSION)
    }
}
