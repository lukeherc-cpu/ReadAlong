package com.example.readalong.domain.link

import org.json.JSONArray
import org.json.JSONObject

/**
 * One bookmark as it travels between devices. Its place is kept the way `.readalong-progress` keeps one: the
 * audio file it is in and the time within that file, plus the whole-book time, so each side can find it even
 * when the two devices split the book differently.
 */
data class SharedBookmark(val id: String, val name: String, val createdAt: Long, val positionMs: Long, val durationMs: Long,
    val file: String?, val fileIndex: Int, val filePositionMs: Long, val fileCount: Int?)

/** A book's bookmarks, and the ids of bookmarks deleted on either device so they aren't brought back. */
data class BookmarkFile(val bookmarks: List<SharedBookmark>, val removed: Set<String>)

/**
 * "<book>.readalong-bookmarks" (format "readalong-bookmarks", version 1), beside the book like its
 * `.readalong-progress`. The phone and ReadAlong Desktop each merge it with their own bookmarks on every
 * library scan and after a bookmark is added or removed, then write the merged list back: a bookmark made on
 * either device appears on the other, and one deleted on either is deleted on both.
 */
object BookmarkFiles {
    const val FORMAT = "readalong-bookmarks"
    const val EXTENSION = ".readalong-bookmarks"

    fun parse(text: String): BookmarkFile? = runCatching {
        if (text.length > 2 * 1024 * 1024) return null
        val o = JSONObject(text)
        if (o.optString("format") != FORMAT || o.optInt("version", 0) != 1) return null
        val marks = o.optJSONArray("bookmarks") ?: JSONArray()
        val removed = o.optJSONArray("removed") ?: JSONArray()
        BookmarkFile(List(marks.length()) { i -> marks.getJSONObject(i).let { m ->
            SharedBookmark(m.getString("id"), m.optString("name"), m.optLong("createdAt"), m.optLong("positionMs").coerceAtLeast(0), m.optLong("durationMs"),
                m.optString("file").takeIf { !m.isNull("file") && it.isNotBlank() }, m.optInt("fileIndex", -1), m.optLong("filePositionMs").coerceAtLeast(0),
                if (m.has("fileCount")) m.optInt("fileCount") else null)
        } }.distinctBy { it.id }, (0 until removed.length()).mapTo(HashSet()) { removed.getString(it) })
    }.getOrNull()

    fun encode(file: BookmarkFile, device: String): String = JSONObject().put("format", FORMAT).put("version", 1).put("device", device)
        .put("bookmarks", JSONArray(file.bookmarks.sortedBy { it.createdAt }.map { b ->
            JSONObject().put("id", b.id).put("name", b.name).put("createdAt", b.createdAt).put("positionMs", b.positionMs).put("durationMs", b.durationMs)
                .put("file", b.file ?: JSONObject.NULL).put("fileIndex", b.fileIndex).put("filePositionMs", b.filePositionMs).apply { b.fileCount?.let { put("fileCount", it) } }
        }))
        .put("removed", JSONArray(file.removed.sorted())).toString(1)

    /** What a device does with its own bookmarks ([local], ids it deleted in [localRemoved]) and the folder's file. */
    data class Merge(
        /** Bookmarks from the file this device doesn't have yet. */
        val add: List<SharedBookmark>,
        /** Ids of this device's bookmarks deleted on the other device. */
        val delete: Set<String>,
        /** The file to write back, or null when it already says the same. */
        val write: BookmarkFile?,
        /** Every deletion known to either side, for this device to remember. */
        val removed: Set<String>,
    )

    fun merge(local: List<SharedBookmark>, localRemoved: Set<String>, file: BookmarkFile?): Merge {
        val removed = localRemoved + file?.removed.orEmpty()
        val mine = local.filter { it.id !in removed }
        val known = mine.mapTo(HashSet()) { it.id }
        val add = file?.bookmarks.orEmpty().filter { it.id !in removed && it.id !in known }
        val delete = local.filter { it.id in removed }.mapTo(HashSet()) { it.id }
        val merged = BookmarkFile(mine + add, removed)
        val same = file != null && file.removed == merged.removed && file.bookmarks.map { it.id }.toSet() == merged.bookmarks.map { it.id }.toSet()
        // Nothing to say yet: leave the folder alone until there is a bookmark or a deletion.
        val write = if (same || (file == null && merged.bookmarks.isEmpty() && merged.removed.isEmpty())) null else merged
        return Merge(add, delete, write, removed)
    }
}
