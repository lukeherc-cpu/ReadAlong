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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Writes `<book>.readalong` beside each book the phone has transcribed, in the format ReadAlong Desktop
 * reads (manifest.json and chunks.jsonl, see DesktopTranscriptImporter), so a linked computer gets the
 * text with the next folder sync (the link, OneDrive, Syncthing...) instead of transcribing the book again.
 * Every saved folder the phone can write to gets one, unless "ReadAlong files" is turned off in Settings › Sync;
 * a book whose text came from the computer, or that already has a `.readalong` beside it, is left alone.
 */
class TranscriptPackageWriter(private val context: Context, private val database: ReadAlongDatabase, private val link: LinkStore) {
    private val resolver = context.contentResolver

    /** Writes the packages still missing in synced folders; returns how many were written. Never throws for one book. */
    suspend fun writeAll(): Int = withContext(Dispatchers.IO) {
        // "ReadAlong files" turned off in Settings › Sync: none are written.
        if (com.example.readalong.domain.link.FolderSyncPlan.Kind.TRANSCRIPT.id in link.value.skipped) return@withContext 0
        writing.withLock {
            var written = 0
            for (entry in database.bookDao().metadataWithPrefix(PROGRESS_KEY)) {
                coroutineContext.ensureActive()
                val bookId = entry.key.removePrefix(PROGRESS_KEY)
                try { if (write(bookId, FolderProgressLocation.decode(entry.value) ?: continue)) written++ }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { android.util.Log.i("ReadAlong", "Transcript package not written: ${failure.javaClass.simpleName}: ${failure.message}") }
            }
            written
        }
    }

    private suspend fun write(bookId: String, location: FolderProgressLocation): Boolean {
        val books = database.bookDao()
        val job = database.processingDao().find(bookId) ?: return false
        if (job.state != "READY") return false
        // Text that came from the computer is already there.
        if (books.metadata("desktop_transcript_engine:$bookId") != null) return false
        val signature = "${job.workId}:${job.sourceFingerprint}"
        if (books.metadata(WRITTEN_KEY + bookId) == signature) return false
        val folder = Uri.parse(location.folder)
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
            val (size, sha256) = AudioHashes.sha256(context, database, file.uri)
            sources.put(JSONObject().put("name", file.name).put("size", size).put("sha256", sha256).put("durationMs", file.durationMs))
        }
        // Segments sorted by time: each unit finds its run by binary search instead of scanning every segment.
        val ordered = segments.sortedBy { it.startMs }
        fun firstAtOrAfter(ms: Long): Int { var low = 0; var high = ordered.size
            while (low < high) { val mid = (low + high) ushr 1; if (ordered[mid].startMs < ms) low = mid + 1 else high = mid }
            return low }
        val lines = plan.map { unit ->
            val index = files.indexOfFirst { it.uri == unit.audioUri }
            check(index >= 0) { "The plan and the book's files disagree." }
            val length = unit.endMs - unit.startMs
            val within = ordered.subList(firstAtOrAfter(unit.startMs), firstAtOrAfter(unit.endMs).coerceAtLeast(firstAtOrAfter(unit.startMs)))
            JSONObject().put("file", index).put("startMs", unit.fileStartMs).put("endMs", unit.fileStartMs + length)
                .put("result", chunkResult(within, unit.startMs, length)).toString()
        }
        // A package with no text would make the computer show an empty ReadAlong; keep this book's text to itself instead.
        if (lines.none { JSONObject(it).getJSONObject("result").optString("text").isNotBlank() }) {
            android.util.Log.i("ReadAlong", "Transcript package not written: no text")
            return false
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
        /** A scan and a sync can both ask for packages; two writers would make "name (1).readalong". */
        private val writing = kotlinx.coroutines.sync.Mutex()

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
            // Readers (WhistleResult) refuse a section whose word times go backwards, so each starts no earlier than the one before.
            var previous = 0.0
            words.forEach { word ->
                val start = ((word.startMs - unitStartMs) / 1000.0).coerceIn(previous, limit)
                previous = start
                val end = ((word.endMs - unitStartMs) / 1000.0).coerceIn(start, limit)
                array.put(JSONObject().put("word", word.word.trim()).put("start", start).put("end", end).apply {
                    word.confidence?.toDouble()?.takeIf { it in 0.0..1.0 }?.let { put("probability", it) }
                })
            }
            return JSONObject().put("text", text).put("words", array)
        }
    }
}
