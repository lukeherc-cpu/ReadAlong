package com.example.readalong.data.importing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.room.withTransaction
import com.example.readalong.data.local.AudioFileEntity
import com.example.readalong.data.local.BookmarkEntity
import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.domain.link.BookmarkFile
import com.example.readalong.domain.link.BookmarkFiles
import com.example.readalong.domain.link.SharedBookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Bookmarks shared with ReadAlong Desktop (or another phone) through `<book>.readalong-bookmarks` beside the
 * book (see [BookmarkFiles]). Each folder scan merges the file with this phone's bookmarks, and adding or
 * removing a bookmark writes it again. Deleted bookmarks are remembered so the other device deletes them too.
 */
class FolderBookmarks(private val context: Context, private val database: ReadAlongDatabase) {
    private val resolver = context.contentResolver
    private val books = database.bookDao()
    private val reading = database.readingDao()
    private val lock = Mutex()

    /** Called by each folder scan for each book it found (with the place its progress file lives). */
    suspend fun sync(bookId: String, location: FolderProgressLocation) = withContext(Dispatchers.IO) { lock.withLock { merge(bookId, location) } }

    /** After a bookmark was added or removed on the phone. */
    suspend fun push(bookId: String) = withContext(Dispatchers.IO) {
        val location = FolderProgressLocation.decode(books.metadata("folder_progress:$bookId")) ?: return@withContext
        lock.withLock { merge(bookId, location) }
    }

    /** Remembers that [id] was deleted here, so the merge deletes it on the other device instead of bringing it back. */
    suspend fun removed(bookId: String, id: String) {
        val known = removedIds(bookId)
        books.putMetadata(LibraryMetadataEntity(REMOVED + bookId, JSONArray((known + id).sorted()).toString()))
    }

    private suspend fun merge(bookId: String, location: FolderProgressLocation) {
        val folder = Uri.parse(location.folder)
        val name = BookFolderFiles.baseName(location) + BookmarkFiles.EXTENSION
        val existing = child(folder, name)
        val shared = existing?.let { uri -> runCatching { resolver.openInputStream(uri)?.use { BookmarkFiles.parse(it.reader().readText()) } }.getOrNull() }
        val files = database.mediaDao().audioFiles(bookId)
        if (files.isEmpty()) return
        val mine = reading.bookmarkList(bookId)
        val result = BookmarkFiles.merge(mine.map { share(it, files) }, removedIds(bookId), shared)
        val added = result.add.mapNotNull { mark -> place(mark, files)?.let { at ->
            BookmarkEntity(mark.id, bookId, at, reading.at(bookId, at)?.id, null, mark.name.takeIf { it.isNotBlank() }?.take(120), mark.createdAt)
        } }
        if (added.isNotEmpty() || result.delete.isNotEmpty()) database.withTransaction {
            if (books.findBook(bookId) == null) return@withTransaction
            result.delete.forEach { reading.deleteBookmark(it) }
            added.forEach { reading.putBookmark(it) }
        }
        if (result.removed != removedIds(bookId)) books.putMetadata(LibraryMetadataEntity(REMOVED + bookId, JSONArray(result.removed.sorted()).toString()))
        result.write?.let { write(folder, existing, name, it) }
    }

    private fun share(mark: BookmarkEntity, files: List<AudioFileEntity>): SharedBookmark {
        val total = files.sumOf { it.durationMs ?: 0 }
        val (index, within) = FolderProgress(0, mark.audioMs, total, false, null, -1, 0).locate(files) ?: (0 to mark.audioMs)
        return SharedBookmark(mark.id, mark.label.orEmpty(), mark.createdAt, mark.audioMs, total, files[index].name, index, within, files.size)
    }

    /** Where another device's bookmark is in this phone's copy of the book; null when the audio plainly differs. */
    private fun place(mark: SharedBookmark, files: List<AudioFileEntity>): Long? {
        val (index, within) = FolderProgress(0, mark.positionMs, mark.durationMs, false, mark.file, mark.fileIndex, mark.filePositionMs, fileCount = mark.fileCount).locate(files) ?: return null
        return files.take(index).sumOf { it.durationMs ?: 0 } + within
    }

    private fun write(folder: Uri, existing: Uri?, name: String, file: BookmarkFile) {
        // Folders the phone can only read keep their bookmarks on this phone.
        try {
            val target = existing ?: DocumentsContract.createDocument(resolver, folder, "application/octet-stream", name) ?: return
            val stream = try { resolver.openOutputStream(target, "wt") } catch (_: IllegalArgumentException) { resolver.openOutputStream(target, "w") }
            requireNotNull(stream).use { it.write(BookmarkFiles.encode(file, "Android").toByteArray(Charsets.UTF_8)) }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            android.util.Log.i("ReadAlong", "Bookmarks file not written: ${failure.javaClass.simpleName}")
        }
    }

    private suspend fun removedIds(bookId: String): Set<String> = runCatching {
        JSONArray(books.metadata(REMOVED + bookId) ?: return emptySet()).let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } }
    }.getOrDefault(emptySet())

    private fun child(folder: Uri, name: String): Uri? = runCatching {
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder)),
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1).equals(name, ignoreCase = true))
                return@use DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0))
            null
        }
    }.getOrNull()

    private companion object { const val REMOVED = "bookmarks_removed:" }
}
