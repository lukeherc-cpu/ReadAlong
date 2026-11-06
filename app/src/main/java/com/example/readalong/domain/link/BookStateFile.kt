package com.example.readalong.domain.link

import com.example.readalong.domain.update.FormatVersion
import org.json.JSONObject

/**
 * "<book>.readalong-state" (format "readalong-state", version 1): everything about a book that the phone and
 * ReadAlong Desktop share besides its transcript, EPUB and cover, in one small file beside the book. It holds
 * one part per kind of information, each in the form its own file used to have:
 *
 * - "progress": the listening position ("readalong-progress"),
 * - "bookmarks": bookmarks and deleted bookmark ids ("readalong-bookmarks", see [BookmarkFiles]),
 * - "info": a title and author someone edited ("readalong-info", see [BookInfoFiles]).
 *
 * Each app reads and writes only its own part and keeps the others as they are. Before this file, each part
 * was a file of its own; [absorb] folds those in, and the apps then delete them.
 */
object BookStateFiles {
    const val FORMAT = "readalong-state"
    const val VERSION = 1
    const val EXTENSION = ".readalong-state"
    /** Far more than any book needs (bookmarks are capped at 2 MB); a larger file isn't ours. */
    const val MAX_BYTES = 4 * 1024 * 1024

    enum class Part(val key: String) {
        PROGRESS("progress"), BOOKMARKS("bookmarks"), INFO("info");
        /** The format name the part carries, and had as a file of its own. */
        val format get() = "readalong-$key"
        /** The separate file this part used to be: "<book>.readalong-progress" and so on. */
        val oldExtension get() = ".$format"
    }

    /** The whole file, or null when [text] is missing, isn't one, or comes from a version too new to read. */
    private fun root(text: String?): JSONObject? = text?.takeIf { it.isNotBlank() && it.length <= MAX_BYTES }?.let {
        runCatching { JSONObject(it).takeIf { o -> o.optString("format") == FORMAT && FormatVersion.readable(o, VERSION) } }.getOrNull()
    }

    private fun fresh() = JSONObject().put("format", FORMAT).put("version", VERSION)

    /** Whether [text] can be written over: no file yet, or one this version reads. A newer or foreign file is left alone. */
    fun writable(text: String?) = text.isNullOrBlank() || root(text) != null

    /** [part] as text in its own format (what FolderProgress, [BookmarkFiles] and [BookInfoFiles] parse), or null. */
    fun section(text: String?, part: Part): String? = root(text)?.optJSONObject(part.key)?.toString()

    /** [text] with [part] replaced by [section], or null when [text] can't be written over (see [writable]). */
    fun withSection(text: String?, part: Part, section: String, device: String): String? {
        val root = if (text.isNullOrBlank()) fresh() else root(text) ?: return null
        return root.put("device", device).put(part.key, JSONObject(section)).toString(1)
    }

    /**
     * Folds a book's old separate files ([old], by part) into its state file [text]. A part only one of them has
     * is copied; where both have it, the newer listening, the newer edit, and every bookmark and deletion from
     * either are kept. Returns the new text, or null when [text] can't be written over.
     */
    fun absorb(text: String?, old: Map<Part, String>, device: String): String? {
        val root = if (text.isNullOrBlank()) fresh() else root(text) ?: return null
        for ((part, oldText) in old) {
            val incoming = runCatching { JSONObject(oldText) }.getOrNull()?.takeIf { it.optString("format") == part.format } ?: continue
            val current = root.optJSONObject(part.key)
            root.put(part.key, if (current == null) incoming else combine(part, current, incoming, device))
        }
        return root.put("device", device).toString(1)
    }

    private fun combine(part: Part, current: JSONObject, incoming: JSONObject, device: String): JSONObject = when (part) {
        Part.PROGRESS -> if (incoming.optLong("updatedAt") > current.optLong("updatedAt")) incoming else current
        Part.INFO -> if (incoming.optLong("editedAt") > current.optLong("editedAt")) incoming else current
        Part.BOOKMARKS -> {
            val a = BookmarkFiles.parse(current.toString()); val b = BookmarkFiles.parse(incoming.toString())
            if (a == null || b == null) (if (a == null) incoming else current) else {
                val removed = a.removed + b.removed
                val marks = (a.bookmarks + b.bookmarks).distinctBy { it.id }.filter { it.id !in removed }
                JSONObject(BookmarkFiles.encode(BookmarkFile(marks, removed), device))
            }
        }
    }
}
