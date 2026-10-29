package com.example.readalong.data.transcription

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.readalong.data.local.*
import com.example.readalong.data.processing.*
import com.example.readalong.data.reading.toEntity
import com.example.readalong.domain.model.TranscriptIndex
import com.example.readalong.domain.transcription.TranscriptionInput
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.math.abs

/** A desktop package is data, never executable content. Validate everything before replacing text. */
class DesktopTranscriptImporter(private val context: Context, private val database: ReadAlongDatabase,
    private val processing: ProcessingRepository, private val epub: com.example.readalong.data.epub.GeneratedEpubRepository) {
    suspend fun import(bookId: String, uri: Uri) = withContext(Dispatchers.IO) {
        val staged = File.createTempFile("transcript-", ".zip", context.cacheDir)
        var importedEngine = "Cactus Whistle"
        var importedModelId = SpeechModelStore.ID
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> staged.outputStream().use { out ->
                var bytes = 0L; val buffer = ByteArray(64 * 1024)
                while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break
                    bytes += n; require(bytes <= 64L * 1024 * 1024) { "Transcript package is too large." }; out.write(buffer, 0, n)
                }
            } }
            val fingerprint = TranscriptionPlan.fingerprint(database, bookId)
            val libraryFiles = database.mediaDao().audioFiles(bookId)
            val chapters = database.mediaDao().chapters(bookId)
            val segments = mutableListOf<com.example.readalong.domain.model.TranscriptSegment>()
            ZipFile(staged).use { zip ->
                val entries = zip.entries().toList()
                require(entries.size == 2 && entries.map { it.name }.toSet() == setOf("manifest.json", "chunks.jsonl")) { "Choose a ReadAlong desktop transcript (.readalong)." }
                val header = zip.getInputStream(zip.getEntry("manifest.json")).use { input -> val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 1024 * 1024); out.write(buffer, 0, n) }; out.toByteArray() }
                require(header.size <= 1024 * 1024)
                val manifest = JSONObject(header.toString(Charsets.UTF_8))
                require(manifest.getString("format") == "readalong-transcript" && manifest.getInt("version") == 1 && manifest.getBoolean("complete")) { "This transcript format is not supported or is incomplete." }
                importedModelId = manifest.getString("model")
                // ReadAlong for Windows writes "desktop:<backend>:<model>" and the engine's name, e.g. "Whisper small.en".
                val desktop = importedModelId.startsWith("desktop:") && importedModelId.length <= 120
                require(desktop || importedModelId in setOf(SpeechModelStore.ID, "whisper-tiny-v1")) { "This transcript was made with an unsupported speech model." }
                importedEngine = when {
                    desktop -> manifest.optString("engine").trim().take(60).ifEmpty { "Whisper" }
                    importedModelId == "whisper-tiny-v1" -> "Whisper Tiny"
                    else -> "Cactus Whistle"
                }
                val sources = manifest.getJSONArray("sources")
                require(libraryFiles.isNotEmpty() && sources.length() == libraryFiles.size && libraryFiles.size <= 4096) { "This transcript must contain the same audiobook audio files." }
                // Match source bytes, not desktop filenames/paths or incidental selection order.
                val hashes = mutableMapOf<String, MutableList<AudioFileEntity>>()
                val canonicalOffsets = mutableMapOf<String, Long>()
                var offset = 0L
                for (file in libraryFiles) {
                    canonicalOffsets[file.id] = offset; offset += requireNotNull(file.durationMs)
                    val hash = MessageDigest.getInstance("SHA-256"); var size = 0L
                    requireNotNull(context.contentResolver.openInputStream(Uri.parse(file.uri))).use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; size += n; hash.update(buffer, 0, n) }
                    }
                    val key = "$size:" + hash.digest().joinToString("") { "%02x".format(it) }
                    hashes.getOrPut(key) { mutableListOf() }.add(file)
                }
                val files = mutableListOf<AudioFileEntity>()
                val durations = LongArray(libraryFiles.size)
                for (i in libraryFiles.indices) {
                    val expected = sources.getJSONObject(i)
                    val matches = hashes["${expected.getLong("size")}:${expected.getString("sha256")}"]
                    require(!matches.isNullOrEmpty()) { "This transcript belongs to different audio. Use the original audiobook files." }
                    val match = matches.indexOfFirst { it.name == expected.optString("name") }.takeIf { it >= 0 } ?: 0
                    val file = matches.removeAt(match); files += file
                    durations[i] = expected.getLong("durationMs")
                    require(durations[i] in 1..7L * 24 * 60 * 60_000 && abs(durations[i] - requireNotNull(file.durationMs)) <= 1000) { "The transcript's audio duration does not match this book." }
                }
                val offsets = files.map { requireNotNull(canonicalOffsets[it.id]) }
                val ends = LongArray(files.size); var fileIndex = 0; var count = 0; var bytes = 0L
                zip.getInputStream(zip.getEntry("chunks.jsonl")).bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        ensureActive()
                        val line = StringBuilder()
                        while (true) {
                            val c = reader.read(); if (c == -1 || c == 10) break
                            line.append(c.toChar()); require(line.length <= 1024 * 1024) { "A transcript section is too large." }
                        }
                        if (line.isEmpty()) { require(reader.read() == -1) { "Unexpected blank transcript section." }; break }
                        bytes += line.length; require(bytes <= 128L * 1024 * 1024)
                        val chunk = JSONObject(line.toString()); val f = chunk.getInt("file")
                        if (fileIndex < files.lastIndex && ends[fileIndex] == durations[fileIndex]) fileIndex++
                        require(f == fileIndex && f in files.indices)
                        val start = chunk.getLong("startMs"); val end = chunk.getLong("endMs")
                        require(start == ends[f] && end - start in 1..30_000 && end <= durations[f]) { "Transcript sections are missing, duplicated or out of order." }
                        ends[f] = end; count++; require(count <= 120_000)
                        val localEnd = minOf(end, files[f].durationMs!!)
                        if (start < localEnd) {
                            val chapter = chapters.lastOrNull { it.fileId == files[f].id && it.startMs <= start }?.id ?: files[f].id
                            val input = TranscriptionInput(bookId, chapter, files[f].uri, offsets[f] + start, offsets[f] + localEnd, false, start)
                            val result = chunk.getJSONObject("result")
                            require(result.getString("text").length <= 100_000)
                            result.optJSONArray("words")?.let { words ->
                                require(words.length() <= 5000)
                                for (w in 0 until words.length()) {
                                    val word = words.getJSONObject(w)
                                    val a = word.getDouble("start"); val b = word.getDouble("end")
                                    require(a.isFinite() && b.isFinite() && a >= 0 && b >= a && b <= (end - start) / 1000.0 + .25) { "Invalid transcript word timestamps." }
                                    if (word.has("probability")) require(word.getDouble("probability") in 0.0..1.0)
                                }
                            }
                            segments += WhistleResult.segments(result.toString(), input).map { segment ->
                                val actualChapter = chapters.lastOrNull { it.fileId == files[f].id && offsets[f] + it.startMs <= segment.startMs }?.id ?: files[f].id
                                segment.copy(chapterId = actualChapter)
                            }
                            require(segments.size <= 1_000_000)
                        }
                    }
                }
                require(count == manifest.getInt("chunks") && ends.contentEquals(durations)) { "The desktop transcript is incomplete." }
            }
            segments.sortBy { it.startMs }
            TranscriptIndex(segments)
            val plan = TranscriptionPlan.inputs(database, bookId)
            processing.replaceWithImportedTranscript(bookId, fingerprint) {
                database.withTransaction {
                    val reading = database.readingDao()
                    val previous = reading.findVersion(bookId)
                    database.importedEpubDao().resetTranscriptMapping(bookId)
                    database.bookDao().putMetadata(LibraryMetadataEntity(com.example.readalong.data.audio.Mp3Timing.versionKey(bookId), com.example.readalong.data.audio.Mp3Timing.VERSION))
                    database.bookDao().putMetadata(LibraryMetadataEntity("desktop_transcript_engine:$bookId", importedModelId))
                    reading.clearTranscript(bookId)
                    segments.chunked(500).forEach { reading.putSegments(it.map { s -> s.toEntity() }) }
                    val chapterCount = plan.map { it.chapterId }.distinct().size
                    reading.putVersion((previous ?: ReadableVersionEntity(bookId, "READY", null, chapterCount, chapterCount, null, 0)).copy(
                        state = "READY", completedChapters = chapterCount, totalChapters = chapterCount,
                        transcriptRevision = (previous?.transcriptRevision ?: 0) + 1, message = "Desktop $importedEngine transcript imported.", updatedAt = System.currentTimeMillis()))
                    val old = database.processingDao().find(bookId)
                    database.processingDao().put(ProcessingJobEntity(bookId, UUID.randomUUID().toString(), SpeechModelStore.ID, "READY", plan.size, plan.size,
                        "Desktop $importedEngine transcript imported. No phone transcription needed.", System.currentTimeMillis(), TranscriptionPlan.encode(plan), fingerprint, old?.queuePosition ?: 0))
                }
            }
            // The canonical transcript is durable even if edition generation is interrupted.
            if (segments.isNotEmpty()) epub.generate(bookId)
            processing.advanceQueue()
        } finally { staged.delete() }
    }
}
