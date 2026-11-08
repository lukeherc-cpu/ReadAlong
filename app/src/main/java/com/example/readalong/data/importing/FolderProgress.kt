package com.example.readalong.data.importing

import android.content.Context
import androidx.room.withTransaction
import com.example.readalong.data.local.AudioFileEntity
import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.domain.link.ProgressMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Listening progress shared with ReadAlong for Windows (or another phone) through the audiobook folder: the
 * "progress" part of `<book>.readalong-state` (see [FolderBookState]; older versions wrote it as a file of its own,
 * `<book>.readalong-progress`), beside the book with the same base name as its `.readalong` transcript, so
 * whatever already syncs the folder (OneDrive, Syncthing, a cable) carries it. Each folder scan reads it, and the newer listening wins: an older phone position takes the file's,
 * a newer one rewrites the file. The phone also writes it when playback pauses.
 */
data class FolderProgress(
    val updatedAt: Long, val positionMs: Long, val durationMs: Long, val finished: Boolean,
    val file: String?, val fileIndex: Int, val filePositionMs: Long, val device: String = "Android", val fileCount: Int? = null,
) {
    fun toJson(): String = JSONObject().put("format", FORMAT).put("version", VERSION).put("device", device)
        .put("updatedAt", updatedAt).put("positionMs", positionMs).put("durationMs", durationMs).put("finished", finished)
        .put("file", file ?: JSONObject.NULL).put("fileIndex", fileIndex).put("filePositionMs", filePositionMs)
        .apply { fileCount?.let { put("fileCount", it) } }.toString(2)

    /**
     * Where this progress is in a book made of [files] (in play order): by file name first, then by
     * file number when both sides have as many files (or, without a count, the same length), then the whole-book position when the lengths
     * agree. Null when the file plainly belongs to different audio. Returns file index and position in it.
     */
    fun locate(files: List<AudioFileEntity>): Pair<Int, Long>? {
        if (files.isEmpty()) return null
        val total = files.sumOf { it.durationMs ?: 0 }
        val sameLength = total > 0 && kotlin.math.abs(total - durationMs) <= 5_000
        val index = file?.let { name -> files.indices.singleOrNull { files[it].name.equals(name, ignoreCase = true) } }
            ?: fileIndex.takeIf { (fileCount?.let { it == files.size } ?: sameLength) && file != null && it in files.indices }
        if (index != null) return index to filePositionMs.coerceIn(0, files[index].durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE)
        if (!sameLength) return null
        var remaining = positionMs.coerceIn(0, total)
        files.forEachIndexed { i, f ->
            val length = f.durationMs ?: 0
            if (remaining < length || i == files.lastIndex) return i to remaining.coerceAtMost(length)
            remaining -= length
        }
        return null
    }

    companion object {
        const val FORMAT = "readalong-progress"
        const val VERSION = 1
        const val EXTENSION = ".readalong-progress"
        /** Positions within this of each other are the same listening; avoids writing back what was just read. */
        const val SAME_MS = 2_000L

        fun parse(text: String): FolderProgress? = runCatching {
            if (text.length > 64 * 1024) return null
            val o = JSONObject(text)
            if (o.optString("format") != FORMAT || !com.example.readalong.domain.update.FormatVersion.readable(o, VERSION)) return null
            FolderProgress(o.getLong("updatedAt"), o.getLong("positionMs").coerceAtLeast(0), o.optLong("durationMs"), o.optBoolean("finished"),
                o.optString("file").takeIf { !o.isNull("file") && it.isNotBlank() }, o.optInt("fileIndex", -1), o.optLong("filePositionMs").coerceAtLeast(0),
                o.optString("device").take(40), if (o.has("fileCount")) o.optInt("fileCount") else null)
        }.getOrNull()

        /** Where [local] (last played at [localAt], null if never) and [remote] disagree, which way to copy. */
        fun direction(localAt: Long?, remote: FolderProgress?): Direction = when (ProgressMerge.byClock(localAt, remote?.updatedAt)) {
            ProgressMerge.Direction.TAKE -> Direction.TAKE
            ProgressMerge.Direction.WRITE -> Direction.WRITE
            ProgressMerge.Direction.NONE -> Direction.NONE
        }
    }
    enum class Direction { TAKE, WRITE, NONE }
}

/** Where a book's progress file lives: the folder (a document URI), its file name, and the file once it exists. */
data class FolderProgressLocation(val folder: String, val name: String, val file: String?) {
    fun encode(): String = JSONObject().put("folder", folder).put("name", name).put("file", file ?: JSONObject.NULL).toString()
    companion object {
        fun decode(text: String?): FolderProgressLocation? = runCatching {
            val o = JSONObject(requireNotNull(text))
            FolderProgressLocation(o.getString("folder"), o.getString("name"), o.optString("file").takeIf { !o.isNull("file") && it.isNotBlank() })
        }.getOrNull()
    }
}

/**
 * Reads and writes [FolderProgress] files. [playing] says whether a book is playing right now (its
 * position is never replaced then); [moved] lets a player that has the book loaded follow a taken position.
 */
class FolderProgressSync(private val context: Context, private val database: ReadAlongDatabase,
    private val playing: (String) -> Boolean = { false }, private val moved: suspend (String, Long) -> Unit = { _, _ -> }) {
    private val state = FolderBookState(context)
    private val dao = database.bookDao()

    /** Called by each folder scan for each book it found. */
    suspend fun sync(bookId: String, location: FolderProgressLocation) = withContext(Dispatchers.IO) {
        dao.putMetadata(LibraryMetadataEntity(key(bookId), location.encode()))
        val remote = read(location)
        when (decide(bookId, remote)) {
            ProgressMerge.Direction.TAKE -> if (!playing(bookId)) take(bookId, requireNotNull(remote))
            ProgressMerge.Direction.WRITE -> write(bookId, location)
            ProgressMerge.Direction.NONE -> Unit
        }
    }

    /** By what changed since the last sync, so a computer clock running fast can't override newer listening here. */
    private suspend fun decide(bookId: String, remote: FolderProgress?): ProgressMerge.Direction {
        val seen = ProgressMerge.decodeSeen(dao.metadata(seenKey(bookId)))
        return ProgressMerge.decide(dao.progress(bookId)?.lastPlayedAt, remote?.updatedAt, seen?.first, seen?.second)
    }
    private suspend fun remember(bookId: String, remoteAt: Long?, localAt: Long?) =
        dao.putMetadata(LibraryMetadataEntity(seenKey(bookId), ProgressMerge.encodeSeen(remoteAt, localAt)))

    /** After listening on the phone: write the book's progress beside it, unless the file is already as new. */
    suspend fun push(bookId: String) = withContext(Dispatchers.IO) {
        val location = FolderProgressLocation.decode(dao.metadata(key(bookId))) ?: return@withContext
        val remote = read(location)
        if (decide(bookId, remote) == ProgressMerge.Direction.WRITE) write(bookId, location)
    }

    private suspend fun read(location: FolderProgressLocation): FolderProgress? =
        state.read(location, com.example.readalong.domain.link.BookStateFiles.Part.PROGRESS)?.let(FolderProgress::parse)

    private suspend fun take(bookId: String, remote: FolderProgress) {
        val files = database.mediaDao().audioFiles(bookId)
        val (index, filePosition) = remote.locate(files) ?: return
        val position = files.take(index).sumOf { it.durationMs ?: 0 } + filePosition
        database.withTransaction {
            if (dao.findBook(bookId) == null) return@withTransaction
            dao.insertProgress(listOf(com.example.readalong.data.local.BookProgressEntity(bookId)))
            // The other device's time, so this position isn't written straight back as "newer".
            dao.updateListeningPosition(bookId, position, remote.updatedAt)
            dao.setFinished(bookId, remote.finished)
            // The player resumes from these when it next loads the book.
            dao.putMetadata(LibraryMetadataEntity("file:$bookId", files[index].id))
            dao.putMetadata(LibraryMetadataEntity("file_position:$bookId", filePosition.toString()))
        }
        remember(bookId, remote.updatedAt, remote.updatedAt)
        moved(bookId, position)
    }

    private suspend fun write(bookId: String, location: FolderProgressLocation) {
        val progress = dao.progress(bookId) ?: return
        val playedAt = progress.lastPlayedAt ?: return
        val files = database.mediaDao().audioFiles(bookId)
        if (files.isEmpty()) return
        val savedIndex = files.indexOfFirst { it.id == dao.metadata("file:$bookId") }
        val (index, filePosition) = if (savedIndex >= 0) savedIndex to (dao.metadata("file_position:$bookId")?.toLongOrNull() ?: 0)
            else FolderProgress(0, progress.audioPositionMs, files.sumOf { it.durationMs ?: 0 }, false, null, -1, 0).locate(files) ?: (0 to 0L)
        val content = FolderProgress(playedAt, progress.audioPositionMs, files.sumOf { it.durationMs ?: 0 }, progress.isFinished,
            files[index].name, index, filePosition, fileCount = files.size).toJson()
        // Folders added before write access was asked for just don't send progress back.
        if (state.write(location, com.example.readalong.domain.link.BookStateFiles.Part.PROGRESS, content)) remember(bookId, playedAt, playedAt)
    }

    private fun key(bookId: String) = "folder_progress:$bookId"
    private fun seenKey(bookId: String) = "folder_progress_seen:$bookId"
}
