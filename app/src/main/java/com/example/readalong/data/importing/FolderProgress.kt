package com.example.readalong.data.importing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.example.readalong.data.local.AudioFileEntity
import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.data.local.ReadAlongDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Listening progress shared with ReadAlong for Windows (or another phone) through the audiobook folder:
 * `<book>.readalong-progress` sits beside the book, in the same place and with the same base name as its
 * `.readalong` transcript, so whatever already syncs the folder (OneDrive, Syncthing, a cable) carries
 * it. Each folder scan reads it, and the newer listening wins: an older phone position takes the file's,
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
            if (o.optString("format") != FORMAT || o.optInt("version", 0) !in 1..VERSION) return null
            FolderProgress(o.getLong("updatedAt"), o.getLong("positionMs").coerceAtLeast(0), o.optLong("durationMs"), o.optBoolean("finished"),
                o.optString("file").takeIf { !o.isNull("file") && it.isNotBlank() }, o.optInt("fileIndex", -1), o.optLong("filePositionMs").coerceAtLeast(0),
                o.optString("device").take(40), if (o.has("fileCount")) o.optInt("fileCount") else null)
        }.getOrNull()

        /** Where [local] (last played at [localAt], null if never) and [remote] disagree, which way to copy. */
        fun direction(localAt: Long?, remote: FolderProgress?): Direction = when {
            remote != null && remote.updatedAt > (localAt ?: 0) + SAME_MS -> Direction.TAKE
            localAt != null && (remote == null || localAt > remote.updatedAt + SAME_MS) -> Direction.WRITE
            else -> Direction.NONE
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
    private val resolver = context.contentResolver
    private val dao = database.bookDao()

    /** Called by each folder scan for each book it found. */
    suspend fun sync(bookId: String, location: FolderProgressLocation) = withContext(Dispatchers.IO) {
        dao.putMetadata(LibraryMetadataEntity(key(bookId), location.encode()))
        val remote = location.file?.let { read(Uri.parse(it)) }
        when (FolderProgress.direction(dao.progress(bookId)?.lastPlayedAt, remote)) {
            FolderProgress.Direction.TAKE -> if (!playing(bookId)) take(bookId, requireNotNull(remote))
            FolderProgress.Direction.WRITE -> write(bookId, location)
            FolderProgress.Direction.NONE -> Unit
        }
    }

    /** After listening on the phone: write the book's progress beside it, unless the file is already as new. */
    suspend fun push(bookId: String) = withContext(Dispatchers.IO) {
        val location = FolderProgressLocation.decode(dao.metadata(key(bookId))) ?: return@withContext
        val remote = location.file?.let { read(Uri.parse(it)) }
        if (FolderProgress.direction(dao.progress(bookId)?.lastPlayedAt, remote) == FolderProgress.Direction.WRITE) write(bookId, location)
    }

    private fun read(uri: Uri): FolderProgress? = runCatching {
        resolver.openInputStream(uri)?.use { input -> FolderProgress.parse(String(input.readNBytesCompat(64 * 1024 + 1), Charsets.UTF_8)) }
    }.getOrNull()

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
            files[index].name, index, filePosition, fileCount = files.size).toJson().toByteArray(Charsets.UTF_8)
        // Folders added before this release only granted reading; those just don't send progress back.
        try {
            val target = location.file?.let(Uri::parse) ?: DocumentsContract.createDocument(resolver, Uri.parse(location.folder), "application/octet-stream", location.name)
                ?: return
            val stream = try { resolver.openOutputStream(target, "wt") } catch (_: IllegalArgumentException) { resolver.openOutputStream(target, "w") }
            requireNotNull(stream).use { it.write(content) }
            if (location.file == null) dao.putMetadata(LibraryMetadataEntity(key(bookId), location.copy(file = target.toString()).encode()))
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            android.util.Log.i("ReadAlong", "Progress file not written: ${failure.javaClass.simpleName}")
        }
    }

    private fun key(bookId: String) = "folder_progress:$bookId"
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (out.size() < limit) { val n = read(buffer, 0, minOf(buffer.size, limit - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
    return out.toByteArray()
}
