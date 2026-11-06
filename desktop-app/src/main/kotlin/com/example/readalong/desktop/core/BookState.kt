package com.example.readalong.desktop.core

import com.example.readalong.domain.link.BookStateFiles
import com.example.readalong.domain.link.BookStateFiles.Part
import org.json.JSONObject
import java.io.File

/**
 * A book's "<book>.readalong-state" beside it (see [BookStateFiles]): its listening progress, bookmarks and
 * edited title and author in one file, which the phone reads and writes too. [ProgressSync], [BookmarkSync]
 * and [BookInfoSync] each read and write their own part here.
 *
 * Books synced by older versions have a separate "<book>.readalong-progress", ".readalong-bookmarks" and
 * ".readalong-info" instead. Each read or write folds those into the state file and deletes them; where the
 * folder can't be written they stay and are read as they are.
 */
object BookState {
    private val lock = Any()

    fun file(book: LibraryBook): File = book.companionFile(BookStateFiles.EXTENSION.removePrefix("."))
    private fun oldFile(book: LibraryBook, part: Part) = book.companionFile(part.oldExtension.removePrefix("."))

    /** [part] as text in its own format, or null when neither the state file nor an old file has it. */
    fun read(book: LibraryBook, part: Part): String? = synchronized(lock) {
        convert(book)
        BookStateFiles.section(text(file(book)), part) ?: oldFile(book, part).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
    }

    /** Writes [section] as the book's [part], keeping the other parts. Throws where the folder can't be written. */
    fun write(book: LibraryBook, part: Part, section: String) = synchronized(lock) {
        convert(book)
        val target = file(book)
        val updated = checkNotNull(BookStateFiles.withSection(text(target), part, section, AppPaths.platformName)) { "${target.name} is from a newer ReadAlong." }
        target.writeAtomically(updated)
        oldFile(book, part).delete()
    }

    /** Folds the book's old separate files into its state file, then deletes them. Never throws. */
    fun convert(book: LibraryBook) = synchronized(lock) {
        if (book.parts.isEmpty()) return@synchronized
        val old = Part.entries.mapNotNull { part ->
            val f = oldFile(book, part)
            val text = if (f.isFile) text(f) else null
            text?.takeIf { runCatching { JSONObject(it).optString("format") == part.format }.getOrDefault(false) }?.let { part to (f to it) }
        }.toMap()
        if (old.isEmpty()) return@synchronized
        runCatching {
            val target = file(book)
            val updated = BookStateFiles.absorb(text(target), old.mapValues { it.value.second }, AppPaths.platformName) ?: return@synchronized
            target.writeAtomically(updated)
            old.values.forEach { (f, _) -> f.delete() }
        }
    }

    private fun text(file: File): String? = if (file.isFile && file.length() <= BookStateFiles.MAX_BYTES) runCatching { file.readText() }.getOrNull() else null
}
