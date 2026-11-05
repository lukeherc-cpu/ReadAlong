package com.example.readalong.desktop.core

import com.example.readalong.domain.link.BookmarkFiles
import com.example.readalong.domain.link.SharedBookmark

/**
 * Shares bookmarks with the phone through "<book>.readalong-bookmarks" beside each book (see [BookmarkFiles]),
 * the same way [ProgressSync] shares listening positions: each sync merges the file with this computer's
 * bookmarks and writes the merged list back where it changed.
 */
class BookmarkSync(private val library: LibraryStore) {
    fun syncAll() = library.books.value.filter { it.parts.isNotEmpty() }.forEach { runCatching { sync(it) } }

    @Synchronized fun sync(book: LibraryBook) {
        val file = book.companionFile(BookmarkFiles.EXTENSION.removePrefix("."))
        val shared = if (file.isFile) runCatching { BookmarkFiles.parse(file.readText()) }.getOrNull() else null
        val result = BookmarkFiles.merge(book.bookmarks.map { share(book, it) }, book.removedBookmarks.toSet(), shared)
        val added = result.add.mapNotNull { mark -> place(book, mark)?.let { Bookmark(it, mark.name.ifBlank { "Bookmark" }, mark.createdAt, mark.id) } }
        if (added.isNotEmpty() || result.delete.isNotEmpty() || result.removed != book.removedBookmarks.toSet())
            library.update(book.id) { b -> b.copy(bookmarks = (b.bookmarks.filterNot { it.id in result.delete } + added).distinctBy { it.id }.sortedBy { it.positionMs },
                removedBookmarks = result.removed.sorted()) }
        // Where the folder can't be written, bookmarks just stay on this computer.
        result.write?.let { runCatching { file.writeAtomically(BookmarkFiles.encode(it, AppPaths.platformName)) } }
    }

    companion object {
        fun share(book: LibraryBook, mark: Bookmark): SharedBookmark {
            val index = book.partAt(mark.positionMs)
            val part = book.parts[index]
            return SharedBookmark(mark.id, mark.name, mark.createdAt, mark.positionMs, book.durationMs, part.name, index,
                (mark.positionMs - part.offsetMs).coerceAtLeast(0), book.parts.size)
        }

        /** Where the phone's bookmark is in this computer's copy of the book; null when the audio plainly differs. */
        fun place(book: LibraryBook, mark: SharedBookmark): Long? = ProgressSync.resolve(book, SharedProgress("", 0, mark.positionMs, mark.durationMs, false,
            mark.file, mark.fileIndex.takeIf { it >= 0 }, mark.filePositionMs, mark.fileCount))
    }
}
