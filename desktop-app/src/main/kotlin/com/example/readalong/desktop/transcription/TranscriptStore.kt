package com.example.readalong.desktop.transcription

import com.example.readalong.data.transcription.TimedRecognitionResult
import com.example.readalong.data.transcription.WhistleResult
import com.example.readalong.desktop.core.AppPaths
import com.example.readalong.desktop.core.LibraryBook
import com.example.readalong.desktop.core.writeAtomically
import com.example.readalong.domain.model.TranscriptSegment
import com.example.readalong.domain.transcription.TranscriptionInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** One unit of a transcript: [startMs] to [endMs] of audio file [file], at most 30 s, in the phone's chunk format. */
data class Chunk(val file: Int, val startMs: Long, val endMs: Long, val result: JSONObject) {
    fun toJson(): JSONObject = JSONObject().put("file", file).put("startMs", startMs).put("endMs", endMs).put("result", result)
}

data class TranscriptInfo(val model: String, val engine: String, val chunks: Int, val totalChunks: Int, val complete: Boolean, val imported: Boolean)

/**
 * Transcripts are kept in exactly the form of the `.readalong` package the phone imports
 * (DesktopTranscriptImporter): a manifest and `chunks.jsonl`, one line per unit of at most 30 s per
 * audio file, back to back from 0 to the file's length, with Whistle-style word times in seconds from
 * the start of the chunk. Text is always read through the phone's own [WhistleResult], so a book reads
 * the same in both apps, and a package moves between them without conversion.
 */
class TranscriptStore {
    private val mutableRevisions = MutableStateFlow(emptyMap<String, Int>())
    /** Bumped whenever a book's transcript changes, so readers can refresh. */
    val revisions: StateFlow<Map<String, Int>> = mutableRevisions

    private fun folder(bookId: String) = AppPaths.book(bookId)
    private fun chunksFile(bookId: String) = File(folder(bookId), "chunks.jsonl")
    private fun infoFile(bookId: String) = File(folder(bookId), "transcript.json")
    private fun bump(bookId: String) = mutableRevisions.update { it + (bookId to (it[bookId] ?: 0) + 1) }

    fun info(bookId: String): TranscriptInfo? = infoFile(bookId).takeIf { it.isFile }?.let { file ->
        runCatching { JSONObject(file.readText()) }.getOrNull()?.let {
            TranscriptInfo(it.getString("model"), it.optString("engine"), it.optInt("chunks"), it.optInt("totalChunks"), it.optBoolean("complete"), it.optBoolean("imported"))
        }
    }

    fun chunks(bookId: String): List<Chunk> {
        val file = chunksFile(bookId)
        if (!file.isFile) return emptyList()
        return file.useLines { lines ->
            lines.filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching { JSONObject(line).let { Chunk(it.getInt("file"), it.getLong("startMs"), it.getLong("endMs"), it.getJSONObject("result")) } }.getOrNull()
            }.toList()
        }
    }

    /** Starts a new transcript (any previous one is removed). */
    fun begin(bookId: String, model: String, engine: String, totalChunks: Int) {
        chunksFile(bookId).delete()
        writeInfo(bookId, model, engine, 0, totalChunks, complete = false, imported = false)
        bump(bookId)
    }

    @Synchronized fun append(bookId: String, chunk: Chunk, total: Int) {
        chunksFile(bookId).appendText(chunk.toJson().toString() + "\n")
        val info = info(bookId)
        val count = (info?.chunks ?: 0) + 1
        writeInfo(bookId, info?.model ?: "", info?.engine ?: "", count, total, complete = count >= total, imported = false)
        bump(bookId)
    }

    fun remove(bookId: String) { chunksFile(bookId).delete(); infoFile(bookId).delete(); bump(bookId) }

    private fun writeInfo(bookId: String, model: String, engine: String, chunks: Int, total: Int, complete: Boolean, imported: Boolean) =
        infoFile(bookId).writeAtomically(JSONObject().put("model", model).put("engine", engine).put("chunks", chunks)
            .put("totalChunks", total).put("complete", complete).put("imported", imported).toString())

    /** The book's text, timed on its whole-book timeline, read exactly as the phone reads an imported package. */
    fun segments(book: LibraryBook): List<TranscriptSegment> {
        val segments = mutableListOf<TranscriptSegment>()
        for (chunk in chunks(book.id)) {
            val part = book.parts.getOrNull(chunk.file) ?: continue
            val start = part.offsetMs + chunk.startMs
            val end = part.offsetMs + minOf(chunk.endMs, part.durationMs)
            if (end <= start) continue
            val input = TranscriptionInput(book.id, chapterId(book, start), part.path, start, end, false, chunk.startMs)
            runCatching { WhistleResult.segments(chunk.result.toString(), input) }.getOrNull()?.let { found ->
                found.forEach { segment -> if (segments.isEmpty() || segments.last().endMs <= segment.startMs) segments += segment.copy(chapterId = chapterId(book, segment.startMs)) }
            }
        }
        return segments
    }

    /** "c<n>" for the audio chapter that holds [positionMs], or "f<n>" for the file when the book has no chapters. */
    fun chapterId(book: LibraryBook, positionMs: Long): String {
        val chapter = book.chapterAt(positionMs)
        return if (chapter >= 0) "c$chapter" else "f${book.partAt(positionMs)}"
    }

    // ---- .readalong packages -------------------------------------------------------------------

    /** Writes the phone's `.readalong` package: manifest.json and chunks.jsonl, nothing else. Needs a complete transcript. */
    fun exportPackage(book: LibraryBook, destination: File, onProgress: (Float) -> Unit = {}) {
        val info = requireNotNull(info(book.id)) { "This book has no ReadAlong yet." }
        require(info.complete) { "The ReadAlong for this book isn't finished yet." }
        val chunks = chunks(book.id)
        val total = book.parts.sumOf { it.size }.coerceAtLeast(1)
        var hashed = 0L
        val sources = JSONArray()
        book.parts.forEach { part ->
            val (size, sha) = sha256(File(part.path)) { hashed += it; onProgress(hashed.toFloat() / total) }
            sources.put(JSONObject().put("name", part.name).put("size", size).put("sha256", sha).put("durationMs", part.durationMs))
        }
        val manifest = JSONObject().put("format", FORMAT).put("version", 1).put("complete", true).put("model", info.model)
            .put("engine", info.engine).put("chunks", chunks.size).put("sources", sources)
        val partial = File(destination.parentFile, destination.name + ".partial")
        ZipOutputStream(partial.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString(1).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("chunks.jsonl"))
            chunks.forEach { zip.write((it.toJson().toString() + "\n").toByteArray()) }
            zip.closeEntry()
        }
        java.nio.file.Files.move(partial.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Reads a `.readalong` package (from the phone, ReadAlong Desktop's command-line transcriber or this
     * app), checked the way the phone checks it: the same audio files by size and SHA-256, chunks back
     * to back from 0 to each file's length, and valid word times.
     */
    fun importPackage(book: LibraryBook, packageFile: File) {
        require(packageFile.length() <= 64L * 1024 * 1024) { "This transcript package is too large." }
        ZipFile(packageFile).use { zip ->
            val names = zip.entries().toList().map { it.name }.toSet()
            require(names == setOf("manifest.json", "chunks.jsonl")) { "Choose a ReadAlong transcript (.readalong)." }
            val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).use { String(it.readNBytes(1024 * 1024)) })
            require(manifest.getString("format") == FORMAT && manifest.getInt("version") == 1 && manifest.getBoolean("complete")) { "This transcript format is not supported or is incomplete." }
            val sources = manifest.getJSONArray("sources")
            require(sources.length() == book.parts.size) { "This transcript must contain the same audiobook audio files." }
            val hashes = book.parts.indices.groupBy { i -> sha256(File(book.parts[i].path)).let { "${it.first}:${it.second}" } }.mapValues { it.value.toMutableList() }
            // Package file index -> this book's part index.
            val order = IntArray(sources.length()) { i ->
                val source = sources.getJSONObject(i)
                val matches = hashes["${source.getLong("size")}:${source.getString("sha256")}"]
                require(!matches.isNullOrEmpty()) { "This transcript belongs to different audio. Use the original audiobook files." }
                val part = matches.firstOrNull { book.parts[it].name == source.optString("name") } ?: matches.first()
                matches.remove(part)
                require(abs(source.getLong("durationMs") - book.parts[part].durationMs) <= 1000) { "The transcript's audio duration does not match this book." }
                part
            }
            val durations = LongArray(sources.length()) { sources.getJSONObject(it).getLong("durationMs") }
            val ends = LongArray(sources.length())
            val byPart = Array(book.parts.size) { mutableListOf<Chunk>() }
            var count = 0
            zip.getInputStream(zip.getEntry("chunks.jsonl")).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val o = JSONObject(line)
                    val f = o.getInt("file")
                    require(f in durations.indices)
                    val start = o.getLong("startMs"); val end = o.getLong("endMs")
                    require(start == ends[f] && end - start in 1..30_000 && end <= durations[f]) { "Transcript sections are missing, duplicated or out of order." }
                    ends[f] = end
                    val result = o.getJSONObject("result")
                    validateWords(result, end - start)
                    byPart[order[f]] += Chunk(order[f], start, end, result)
                    require(++count <= 120_000)
                }
            }
            require(count == manifest.getInt("chunks") && ends.contentEquals(durations)) { "The transcript is incomplete." }
            val text = byPart.flatMap { it }.joinToString("") { it.toJson().toString() + "\n" }
            File(folder(book.id), "chunks.jsonl").writeAtomically(text)
            writeInfo(book.id, manifest.getString("model"), manifest.optString("engine").take(60).ifBlank { engineName(manifest.getString("model")) },
                count, count, complete = true, imported = true)
            bump(book.id)
        }
    }

    private fun validateWords(result: JSONObject, lengthMs: Long) {
        require(result.getString("text").length <= 100_000)
        val words = result.optJSONArray("words") ?: return
        require(words.length() <= 5000)
        for (w in 0 until words.length()) {
            val word = words.getJSONObject(w)
            val a = word.getDouble("start"); val b = word.getDouble("end")
            require(a.isFinite() && b.isFinite() && a >= 0 && b >= a && b <= lengthMs / 1000.0 + .25) { "Invalid transcript word timestamps." }
            if (word.has("probability")) require(word.getDouble("probability") in 0.0..1.0)
        }
    }

    companion object {
        const val FORMAT = "readalong-transcript"
        const val CHUNK_MS = 30_000L

        fun engineName(model: String) = when {
            model == "whisper-tiny-v1" -> "Whisper Tiny"
            model.startsWith("desktop:") -> "Whisper " + model.substringAfterLast(':')
            else -> "Cactus Whistle"
        }

        /**
         * Whisper's token-level recognition (the JSON TimedRecognitionResult reads, as on the phone) as the
         * package's Whistle-style chunk result: the text, and each word's start and end in seconds from the
         * start of the chunk.
         */
        fun chunkResult(segmentsJson: String, lengthMs: Long): JSONObject {
            val input = TranscriptionInput("chunk", "chunk", null, 0, lengthMs, false)
            val segments = TimedRecognitionResult.segments(segmentsJson, input)
            val words = JSONArray()
            var previous = 0L
            val spoken = segments.flatMap { it.words }.filter { it.word.any(Char::isLetterOrDigit) }
            for (word in spoken) {
                val start = maxOf(previous, word.startMs).coerceAtMost(lengthMs)
                if (start >= lengthMs) continue
                val end = word.endMs.coerceIn(start, lengthMs)
                words.put(JSONObject().put("word", word.word.trim()).put("start", start / 1000.0).put("end", end / 1000.0).apply {
                    word.confidence?.let { put("probability", it.toDouble().coerceIn(0.0, 1.0)) }
                })
                previous = start
            }
            if (words.length() == 0) return JSONObject().put("text", "").put("words", words)
            // The phone finds each word in the text, in order; whisper's own text keeps its spacing and punctuation.
            val joined = segments.joinToString(" ") { it.text.trim() }
            var cursor = 0
            val consistent = (0 until words.length()).all { i ->
                val at = joined.indexOf(words.getJSONObject(i).getString("word"), cursor, ignoreCase = true)
                (at >= cursor).also { if (it) cursor = at + words.getJSONObject(i).getString("word").length }
            }
            val text = if (consistent) joined else (0 until words.length()).joinToString(" ") { words.getJSONObject(it).getString("word") }
            return JSONObject().put("text", text).put("words", words)
        }

        private val hashCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
        fun sha256(file: File, progress: (Long) -> Unit = {}): Pair<Long, String> {
            val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
            hashCache[key]?.let { progress(it.first); return it }
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); size += n; progress(n.toLong()) }
            }
            return (size to digest.digest().joinToString("") { "%02x".format(it) }).also { hashCache[key] = it }
        }
    }
}
