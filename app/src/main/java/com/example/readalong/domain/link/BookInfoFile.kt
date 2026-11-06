package com.example.readalong.domain.link

import org.json.JSONObject

/** A title and author someone typed in, and when (milliseconds since 1970). A blank [author] means none. */
data class BookInfo(val title: String, val author: String?, val editedAt: Long)

/**
 * "<book>.readalong-info" (format "readalong-info", version 1), beside the book like its `.readalong-progress`.
 * It holds the title and author last edited on either device. The phone and ReadAlong Desktop write it when the
 * person edits a book, and on each library scan take it when it is newer than their own edit, so an edit made on
 * either device shows on both. Books nobody edited have no file and keep the names read from their tags.
 */
object BookInfoFiles {
    const val FORMAT = "readalong-info"
    const val EXTENSION = ".readalong-info"

    fun parse(text: String): BookInfo? = runCatching {
        if (text.length > 64 * 1024) return null
        val o = JSONObject(text)
        if (o.optString("format") != FORMAT || o.optInt("version", 0) != 1) return null
        val title = o.optString("title").trim().take(500).ifEmpty { return null }
        BookInfo(title, o.optString("author").takeIf { !o.isNull("author") }?.trim()?.take(500)?.ifEmpty { null }, o.getLong("editedAt"))
    }.getOrNull()

    fun encode(info: BookInfo, device: String): String = JSONObject().put("format", FORMAT).put("version", 1).put("device", device)
        .put("title", info.title).put("author", info.author ?: JSONObject.NULL).put("editedAt", info.editedAt).toString(2)

    /** What to do with this device's edit ([local], null if never edited here) and the folder's [file]. */
    enum class Action { TAKE, WRITE, NONE }

    fun direction(local: BookInfo?, file: BookInfo?): Action = when {
        file != null && (local == null || file.editedAt > local.editedAt) -> if (local != null && local.title == file.title && local.author == file.author) Action.NONE else Action.TAKE
        local != null && (file == null || local.editedAt > file.editedAt) -> Action.WRITE
        else -> Action.NONE
    }
}
