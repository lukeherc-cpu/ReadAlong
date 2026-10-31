package com.example.readalong.data.link

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.documentfile.provider.DocumentFile
import com.example.readalong.data.importing.FolderProgress
import com.example.readalong.data.importing.FolderProgressLocation
import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.data.processing.TranscriptionPlan
import com.example.readalong.data.reading.toSegment
import com.example.readalong.data.transcription.SpeechModelStore
import com.example.readalong.domain.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Writes `<book>.readalong` beside each book the phone has transcribed, in the format ReadAlong Desktop
 * reads (manifest.json and chunks.jsonl, see DesktopTranscriptImporter), so a linked computer gets the
 * text with the next folder sync instead of transcribing the book again. Only books in a folder synced
 * with a computer get one, and only while the phone is linked; a book whose text came from the computer,
 * or that already has a `.readalong` beside it, is left alone.
 */
class TranscriptPackageWriter(private val context: Context, private val database: ReadAlongDatabase, private val link: LinkStore) {
    private val resolver = context.contentResolver

    /** Writes the packages still missing in synced folders; returns how many were written. Never throws for one book. */
    suspend fun writeAll(): Int = withContext(Dispatchers.IO) {
        val state = link.value
        if (state.computer == null || state.pairs.isEmpty()) return@withContext 0
        var written = 0
        for (entry in database.bookDao().metadataWithPrefix(PROGRESS_KEY)) {
            coroutineContext.ensureActive()
            val bookId = entry.key.removePrefix(PROGRESS_KEY)
            try { if (write(bookId, state.pairs, FolderProgressLocation.decode(entry.value) ?: continue)) written++ }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { android.util.Log.i("ReadAlong", "Transcript package not written: ${failure.javaClass.simpleName}: ${failure.message}") }
        }
        written
    }

    private suspend fun write(bookId: String, pairs: List<FolderPair>, location: FolderProgressLocation): Boolean {
        val books = database.bookDao()
        val job = database.processingDao().find(bookId) ?: return false
        if (job.state != "READY") return false
        // Text that came from the computer is already there.
        if (books.metadata("desktop_transcript_engine:$bookId") != null) return false
        val signature = "${job.workId}:${job.sourceFingerprint}"
        if (books.metadata(WRITTEN_KEY + bookId) == signature) return false
        val folder = Uri.parse(location.folder)
        if (pairs.none { inside(folder, Uri.parse(it.localUri)) }) return false
        val name = location.name.removeSuffix(FolderProgress.EXTENSION) + ".readalong"
        if (child(folder, name) != null) { books.putMetadata(LibraryMetadataEntity(WRITTEN_KEY + bookId, signature)); return false }

        val files = database.mediaDao().audioFiles(bookId)
        if (files.isEmpty() || files.any { (it.durationMs ?: 0) <= 0 }) return false
        val plan = TranscriptionPlan.inputs(database, bookId)
        val segments = mutableListOf<TranscriptSegment>()
        while (true) {
            val page = database.readingDao().segments(bookId, 500, segments.size)
            segments += page.map { it.toSegment() }
            if (page.size < 500) break
        }
        if (segments.isEmpty()) return false

        val sources = JSONArray()
        for (file in files) {
            val hash = MessageDigest.getInstance("SHA-256"); var size = 0L
            requireNotNull(resolver.openInputStream(Uri.parse(file.uri))).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) { coroutineContext.ensureActive(); val n = input.read(buffer); if (n < 0) break; size += n; hash.update(buffer, 0, n) }
            }
            sources.put(JSONObject().put("name", file.name).put("size", size).put("sha256", hash.digest().joinToString("") { "%02x".format(it) }).put("durationMs", file.durationMs))
        }
        val lines = plan.map { unit ->
            val index = files.indexOfFirst { it.uri == unit.audioUri }
            check(index >= 0) { "The plan and the book's files disagree." }
            val length = unit.endMs - unit.startMs
            val within = segments.filter { it.startMs >= unit.startMs && it.startMs < unit.endMs }
            JSONObject().put("file", index).put("startMs", unit.fileStartMs).put("endMs", unit.fileStartMs + length)
                .put("result", chunkResult(within, unit.startMs, length)).toString()
        }
        val manifest = JSONObject().put("format", "readalong-transcript").put("version", 1).put("complete", true)
            .put("model", SpeechModelStore.ID).put("engine", "Cactus Whistle").put("chunks", lines.size).put("sources", sources)

        val target = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", name) ?: return false
        try {
            requireNotNull(resolver.openOutputStream(target, "w")).use { out ->
                ZipOutputStream(out.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString(1).toByteArray()); zip.closeEntry()
                    zip.putNextEntry(ZipEntry("chunks.jsonl"))
                    lines.forEach { zip.write((it + "\n").toByteArray()) }
                    zip.closeEntry()
                }
            }
        } catch (failure: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw failure
        }
        books.putMetadata(LibraryMetadataEntity(WRITTEN_KEY + bookId, signature))
        // The next folder scan sees this file as already read, so the phone never re-imports its own text.
        DocumentFile.fromSingleUri(context, target)?.let { file ->
            books.putMetadata(LibraryMetadataEntity("folder_transcript:$bookId", "${file.uri}:${file.length()}:${file.lastModified()}"))
        }
        return true
    }

    /** Is [folder] (a document in some tree) the folder [tree] was granted for, or inside it? */
    private fun inside(folder: Uri, tree: Uri): Boolean = runCatching {
        if (folder.authority != tree.authority) return false
        val root = DocumentsContract.getTreeDocumentId(tree)
        val id = DocumentsContract.getDocumentId(folder)
        id == root || id.startsWith(root.trimEnd('/') + "/") || (root.endsWith(":") && id.startsWith(root))
    }.getOrDefault(false)

    private fun child(folder: Uri, name: String): String? = runCatching {
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder)),
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1).equals(name, ignoreCase = true)) return@use cursor.getString(0)
            null
        }
    }.getOrNull()

    companion object {
        private const val PROGRESS_KEY = "folder_progress:"
        private const val WRITTEN_KEY = "phone_transcript_written:"

        /**
         * One unit's result in the Whistle shape both apps read: the text, and each word's time in seconds from the
         * unit's start. The text is the segments' own; when a word can't be found in it in order (an edited
         * sentence), the words themselves become the text, so the computer can always read it.
         */
        fun chunkResult(segments: List<TranscriptSegment>, unitStartMs: Long, lengthMs: Long): JSONObject {
            val words = segments.flatMap { it.words }.filter { it.startMs >= unitStartMs && it.startMs < unitStartMs + lengthMs }
            var text = segments.joinToString(" ") { it.text.trim() }.trim()
            var cursor = 0
            val fits = words.all { word ->
                val at = text.indexOf(word.word.trim(), cursor, ignoreCase = true)
                if (at < cursor) false else { cursor = at + word.word.trim().length; true }
            }
            if (!fits) text = words.joinToString(" ") { it.word.trim() }
            if (words.isEmpty()) text = ""
            val limit = lengthMs / 1000.0
            val array = JSONArray()
            words.forEach { word ->
                val start = ((word.startMs - unitStartMs) / 1000.0).coerceIn(0.0, limit)
                val end = ((word.endMs - unitStartMs) / 1000.0).coerceIn(start, limit)
                array.put(JSONObject().put("word", word.word.trim()).put("start", start).put("end", end).apply {
                    word.confidence?.toDouble()?.takeIf { it in 0.0..1.0 }?.let { put("probability", it) }
                })
            }
            return JSONObject().put("text", text).put("words", array)
        }
    }
}
