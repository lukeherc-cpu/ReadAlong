package com.example.readalong.desktop.core

import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Where a book's companion files live: "<folder>/<folder name>.<extension>", or "<file name>.<extension>" beside a single-file book. */
fun LibraryBook.companionFile(extension: String): File {
    val first = File(parts.first().path)
    if (key.startsWith("file:")) return File(first.parentFile, "${first.nameWithoutExtension}.$extension")
    val folder = File(folder ?: first.parent)
    return File(folder, "${folder.name}.$extension")
}

/** The listening position shared with the phone through a small file beside the book, the same way transcripts travel. */
data class SharedProgress(val device: String, val updatedAt: Long, val positionMs: Long, val durationMs: Long, val finished: Boolean,
    val file: String?, val fileIndex: Int?, val filePositionMs: Long?, val fileCount: Int? = null)

/**
 * Reads and writes "<book>.readalong-progress" (format "readalong-progress", version 1), which the phone
 * also reads and writes on each folder scan and when playback pauses. The most recent listening wins.
 */
class ProgressSync(private val library: LibraryStore) {
    /** Takes newer positions from the shared files and refreshes files that are older than this computer's. [playing] is left alone. */
    fun syncAll(playing: String?): List<String> =
        library.books.value.filter { it.id != playing && it.parts.isNotEmpty() && sync(it) }.map { it.id }

    /** Syncs one book; returns true when its position came from the shared file. */
    fun sync(book: LibraryBook): Boolean {
        val file = book.companionFile(EXTENSION)
        val shared = if (file.isFile) runCatching { parse(file.readText()) }.getOrNull() else null
        val local = book.lastPlayedAt ?: 0L
        if (shared != null && shared.updatedAt > local + 2_000) {
            val position = resolve(book, shared) ?: return false
            library.update(book.id) { it.copy(positionMs = position, finished = shared.finished, lastPlayedAt = shared.updatedAt) }
            return true
        }
        if (local > 0 && (shared == null || local > shared.updatedAt + 2_000)) write(book)
        return false
    }

    /** Records this computer's position for the phone; quietly does nothing where the folder can't be written. */
    fun write(book: LibraryBook) {
        if (book.parts.isEmpty() || book.lastPlayedAt == null) return
        runCatching { book.companionFile(EXTENSION).writeAtomically(encode(book)) }
    }

    companion object {
        const val EXTENSION = "readalong-progress"
        const val FORMAT = "readalong-progress"

        fun parse(text: String): SharedProgress? {
            val json = JSONObject(text)
            if (json.optString("format") != FORMAT || json.optInt("version", 0) !in 1..1) return null
            return SharedProgress(json.optString("device"), json.getLong("updatedAt"), json.optLong("positionMs"), json.optLong("durationMs"),
                json.optBoolean("finished"), json.optString("file").ifBlank { null }, if (json.has("fileIndex")) json.getInt("fileIndex") else null,
                if (json.has("filePositionMs")) json.getLong("filePositionMs") else null, if (json.has("fileCount")) json.getInt("fileCount") else null)
        }

        fun encode(book: LibraryBook): String {
            val index = book.partAt(book.positionMs)
            val part = book.parts[index]
            return JSONObject().put("format", FORMAT).put("version", 1).put("device", AppPaths.platformName).put("updatedAt", book.lastPlayedAt ?: 0L)
                .put("positionMs", book.positionMs).put("durationMs", book.durationMs).put("finished", book.finished)
                .put("file", part.name).put("fileIndex", index).put("fileCount", book.parts.size).put("filePositionMs", (book.positionMs - part.offsetMs).coerceAtLeast(0)).toString(2)
        }

        /** The position on this book's timeline: by file name, then by file number, then by whole-book time when the lengths agree. */
        fun resolve(book: LibraryBook, shared: SharedProgress): Long? {
            val within = shared.filePositionMs
            if (within != null) {
                shared.file?.let { name -> book.parts.firstOrNull { it.name.equals(name, ignoreCase = true) } }?.let { return clamp(book, it.offsetMs + within) }
                shared.fileIndex?.let { book.parts.getOrNull(it) }
                    ?.takeIf { shared.fileCount?.let { count -> count == book.parts.size } ?: (abs(shared.durationMs - book.durationMs) <= 5_000) }?.let { return clamp(book, it.offsetMs + within) }
            }
            return if (abs(shared.durationMs - book.durationMs) <= 5_000) clamp(book, shared.positionMs) else null
        }

        private fun clamp(book: LibraryBook, position: Long) = position.coerceIn(0, book.durationMs.coerceAtLeast(0))
    }
}
