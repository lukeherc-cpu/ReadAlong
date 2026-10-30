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
