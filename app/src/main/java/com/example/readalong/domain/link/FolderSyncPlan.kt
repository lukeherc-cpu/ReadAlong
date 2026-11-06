package com.example.readalong.domain.link

import org.json.JSONArray
import org.json.JSONObject

/** One file in a synced folder; [path] is relative to the folder, with "/" between parts. */
data class SyncFile(val path: String, val size: Long, val modified: Long) {
    fun toJson(): JSONObject = JSONObject().put("path", path).put("size", size).put("modified", modified)
    companion object { fun fromJson(json: JSONObject) = SyncFile(json.getString("path"), json.getLong("size"), json.getLong("modified")) }
}

/** How a file looked on each side right after the last sync, so the next one can tell which side changed it. */
data class SyncedState(val localSize: Long, val localModified: Long, val remoteSize: Long, val remoteModified: Long)

sealed interface SyncAction {
    val path: String
    data class Download(override val path: String) : SyncAction
    data class Upload(override val path: String) : SyncAction
}

/**
 * Decides what to copy between a phone folder and the computer folder it is paired with.
 *
 * A file only one side has is copied to the other, unless the last sync saw it on both sides (then
 * it was deleted on purpose, and deletions are never copied: nothing is ever deleted). A file that
 * changed on one side since the last sync is copied over the other. When both changed, the newer
 * one wins for small companion files (progress, transcripts, bookmarks); audio is left alone.
 */
object FolderSyncPlan {
    private val audio = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "mp4")
    /** Our own temporary files while a copy is in flight, other apps' partial downloads, and hidden files. */
    private val skippedEndings = listOf(".tmp", ".part", ".partial", ".crdownload", ".readalong-sync")

    fun syncable(path: String): Boolean = safe(path) && path.split('/').none { it.startsWith(".") } && skippedEndings.none { path.endsWith(it, ignoreCase = true) }

    /** A relative path that can't escape its folder. */
    fun safe(path: String): Boolean = path.isNotBlank() && !path.startsWith("/") && '\\' !in path && ':' !in path &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    fun isAudio(path: String) = path.substringAfterLast('.', "").lowercase() in audio

    /**
     * The kinds of companion file a person can leave out of syncing (Settings on either app); audio and
     * anything else always syncs. The ids travel between the apps, so they never change. [SETTINGS] is no file:
     * it is the app and reader settings, which travel over the link itself (see SettingsSync).
     */
    enum class Kind(val id: String) { TRANSCRIPT("transcript"), PROGRESS("progress"), BOOKMARKS("bookmarks"), EPUB("epub"), COVER("cover"), SETTINGS("settings") }
    private val images = setOf("jpg", "jpeg", "png", "webp", "bmp")

    fun kind(path: String): Kind? = when (path.substringAfterLast('.', "").lowercase()) {
        "readalong" -> Kind.TRANSCRIPT
        "readalong-progress", "readalong-state" -> Kind.PROGRESS
        "readalong-bookmarks" -> Kind.BOOKMARKS
        "epub" -> Kind.EPUB
        in images -> Kind.COVER
        else -> null
    }

    /**
     * Whether [path] syncs when the kinds in [skipped] (by [Kind.id]) are turned off. A book's
     * `.readalong-state` holds its progress, bookmarks and edited title together, so it syncs only while
     * neither progress nor bookmarks is turned off.
     */
    fun included(path: String, skipped: Set<String>): Boolean =
        if (path.endsWith(BookStateFiles.EXTENSION, ignoreCase = true)) Kind.PROGRESS.id !in skipped && Kind.BOOKMARKS.id !in skipped
        else kind(path)?.let { it.id !in skipped } ?: true

    /**
     * The "What to sync" checkboxes on both apps: each label and the kind ids it turns off together. Progress and
     * bookmarks share one file (with edited titles and authors), so they share one checkbox.
     */
    val checkboxes: List<Pair<String, Set<String>>> = listOf(
        "ReadAlong transcripts" to setOf(Kind.TRANSCRIPT.id),
        "Listening progress, bookmarks and edited titles" to setOf(Kind.PROGRESS.id, Kind.BOOKMARKS.id),
        "EPUBs" to setOf(Kind.EPUB.id),
        "Cover art" to setOf(Kind.COVER.id),
        "App and reader settings (accent colour, skip times, reading theme and font; text size stays per device)" to setOf(Kind.SETTINGS.id),
    )

    fun encodeKinds(skipped: Set<String>) = JSONArray(skipped.sorted())
    fun decodeKinds(array: JSONArray?): Set<String> = array?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } }.orEmpty()

    fun plan(local: List<SyncFile>, remote: List<SyncFile>, synced: Map<String, SyncedState>): List<SyncAction> {
        val mine = local.filter { syncable(it.path) }.associateBy { it.path }
        val theirs = remote.filter { syncable(it.path) }.associateBy { it.path }
        val actions = ArrayList<SyncAction>()
        for (path in (mine.keys + theirs.keys).sorted()) {
            val l = mine[path]; val r = theirs[path]; val last = synced[path]
            when {
                l == null && r != null -> if (last == null) actions += SyncAction.Download(path)
                r == null && l != null -> if (last == null) actions += SyncAction.Upload(path)
                l != null && r != null -> {
                    val localChanged = last == null || l.size != last.localSize || l.modified != last.localModified
                    val remoteChanged = last == null || r.size != last.remoteSize || r.modified != last.remoteModified
                    when {
                        !localChanged && !remoteChanged && shortCopy(last!!) -> actions += SyncAction.Download(path)
                        last == null && l.size == r.size -> Unit // Already the same file on both sides.
                        localChanged && !remoteChanged -> actions += SyncAction.Upload(path)
                        remoteChanged && !localChanged -> actions += SyncAction.Download(path)
                        localChanged && remoteChanged && !isAudio(path) -> actions += if (l.modified >= r.modified) SyncAction.Upload(path) else SyncAction.Download(path)
                    }
                }
            }
        }
        // Small files first, so progress and transcripts arrive before a long audiobook copy.
        return actions.sortedBy { (mine[it.path] ?: theirs[it.path])?.size ?: 0 }
    }

    /**
     * Phones up to 0.47.0 lost the end of each file they copied from the computer: everything after the last
     * full [LinkChannel] frame (256 KB) of it, so a small file arrived empty and a `.readalong` without the end
     * that makes it a zip. Such a copy is recognized by its record: the phone's size is the computer's rounded
     * down to a whole frame. Copied again once, it is whole.
     */
    fun shortCopy(last: SyncedState): Boolean =
        last.localSize < last.remoteSize && last.localSize == last.remoteSize / LinkChannel.FRAME_DATA * LinkChannel.FRAME_DATA

    /** The record to keep after a sync: every file both sides now have. */
    fun settled(local: List<SyncFile>, remote: List<SyncFile>, previous: Map<String, SyncedState>): Map<String, SyncedState> {
        val theirs = remote.associateBy { it.path }
        val mine = local.mapTo(HashSet()) { it.path }
        val now = local.mapNotNull { l -> theirs[l.path]?.let { r -> l.path to SyncedState(l.size, l.modified, r.size, r.modified) } }.toMap()
        // Keep the record of files deleted on one side, so they aren't copied back next time.
        val deleted = previous.filterKeys { it !in now && (it in theirs || it in mine) }
        return deleted + now
    }

    fun encode(state: Map<String, SyncedState>): String = JSONArray(state.map { (path, s) ->
        JSONObject().put("path", path).put("ls", s.localSize).put("lm", s.localModified).put("rs", s.remoteSize).put("rm", s.remoteModified)
    }).toString()

    fun decode(text: String): Map<String, SyncedState> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).associate { i -> array.getJSONObject(i).let { it.getString("path") to SyncedState(it.getLong("ls"), it.getLong("lm"), it.getLong("rs"), it.getLong("rm")) } }
    }.getOrDefault(emptyMap())
}
