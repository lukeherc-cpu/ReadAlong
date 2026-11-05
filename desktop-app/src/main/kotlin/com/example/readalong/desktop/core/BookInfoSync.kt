package com.example.readalong.desktop.core

import com.example.readalong.domain.link.BookInfo
import com.example.readalong.domain.link.BookInfoFiles

/**
 * Shares a title or author the person edited with the phone through "<book>.readalong-info" beside the book
 * (see [BookInfoFiles]): the newer edit wins on both devices.
 */
class BookInfoSync(private val library: LibraryStore) {
    /** Edits [bookId]'s title and author here and shares them. */
    fun edit(bookId: String, title: String, author: String?) {
        val clean = title.trim().take(500)
        require(clean.isNotEmpty()) { "Enter a title." }
        library.update(bookId) { it.copy(title = clean, author = author?.trim()?.take(500)?.ifEmpty { null }, editedAt = System.currentTimeMillis()) }
        library.book(bookId)?.let { sync(it) }
    }

    /** Takes the phone's newer edit, or writes this computer's. Returns true when the phone's was taken. Never throws. */
    fun sync(book: LibraryBook): Boolean = runCatching {
        if (book.parts.isEmpty()) return false
        val file = book.companionFile(BookInfoFiles.EXTENSION.removePrefix("."))
        val shared = if (file.isFile) BookInfoFiles.parse(file.readText()) else null
        val local = book.editedAt?.let { BookInfo(book.title, book.author, it) }
        when (BookInfoFiles.direction(local, shared)) {
            BookInfoFiles.Action.TAKE -> {
                val taken = requireNotNull(shared)
                library.update(book.id) { it.copy(title = taken.title, author = taken.author, editedAt = taken.editedAt) }
                true
            }
            BookInfoFiles.Action.WRITE -> { file.writeAtomically(BookInfoFiles.encode(requireNotNull(local), AppPaths.platformName)); false }
            BookInfoFiles.Action.NONE -> false
        }
    }.getOrDefault(false)
}
