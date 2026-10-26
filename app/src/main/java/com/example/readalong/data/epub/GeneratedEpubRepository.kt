package com.example.readalong.data.epub

import android.content.Context
import android.net.Uri
import android.util.Xml
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.example.readalong.data.local.*
import com.example.readalong.data.reading.toSegment
import com.example.readalong.domain.epub.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

class GeneratedEpubRepository(private val context: Context, private val database: ReadAlongDatabase) {
    private val lock = Mutex()
    private fun liveKey(bookId: String) = "live_reading_revision:$bookId"
    suspend fun refreshReading(bookId: String) = withContext(Dispatchers.IO) {
        // Usually the reading text is already current. Check that without the lock: while a ReadAlong
        // is being made, the lock is held for each new chunk, and opening the reader must not wait on it.
        if (readingCurrent(bookId)) return@withContext
        lock.withLock { if (!readingCurrent(bookId)) generateLocked(bookId) }
    }
    private suspend fun readingCurrent(bookId: String): Boolean {
        val version = database.readingDao().findVersion(bookId) ?: return true
        if (database.readingDao().count(bookId) == 0) return true
        return version.activeEditionId != null && version.generatedFormat == 1 &&
            database.bookDao().metadata(liveKey(bookId)) == "${version.activeEditionId}:${version.transcriptRevision}"
    }

    /** Update only paragraphs touching a newly committed chunk. ZIP exports remain periodic. */
    suspend fun publishReadingRange(input: com.example.readalong.domain.transcription.TranscriptionInput) = lock.withLock { withContext(Dispatchers.IO) {
        val bookId = input.bookId
        val dao = database.readingDao()
        val version = dao.findVersion(bookId) ?: return@withContext
        if (dao.count(bookId) == 0) return@withContext
        val edition = version.activeEditionId
        if (edition == null || version.generatedFormat != 1 || database.bookDao().metadata(liveKey(bookId)) != "$edition:${version.transcriptRevision - 1}") {
            generateLocked(bookId); return@withContext
        }
        val previous = dao.previousParagraph(bookId, edition, input.chapterId, input.startMs)
        val affected = (dao.affectedParagraphs(bookId, edition, input.chapterId, input.startMs, input.endMs) + listOfNotNull(previous)).distinctBy { it.segmentId }
        val from = minOf(input.startMs, affected.minOfOrNull { it.startMs } ?: input.startMs)
        val until = maxOf(input.endMs, affected.maxOfOrNull { it.endMs } ?: input.endMs)
        val paragraphs = mutableListOf<EpubParagraphEntity>()
        val formatter = ParagraphFormatter { segments ->
            var offset = 0
            val anchors = org.json.JSONArray()
            segments.forEach { segment ->
                anchors.put(org.json.JSONObject().put("id", segment.id).put("startMs", segment.startMs).put("endMs", segment.endMs)
                    .put("start", offset).put("end", offset + segment.text.length))
                offset += segment.text.length + 1
            }
            val first = segments.first()
            paragraphs += EpubParagraphEntity(bookId, edition, first.id, segments.joinToString(" ") { it.text },
                "#${epubFragment(first.id)}", first.chapterId, first.startMs, segments.last().endMs, anchors.toString())
        }
        dao.readingRange(bookId, input.chapterId, from, until).forEach { formatter.add(it.toSegment()) }
        formatter.flush()
        val committed = database.withTransaction {
            if (dao.findVersion(bookId)?.transcriptRevision != version.transcriptRevision) false
            else {
                dao.deleteParagraphs(bookId, edition, affected.map { it.segmentId })
                dao.putEdition(paragraphs)
                database.bookDao().putMetadata(LibraryMetadataEntity(liveKey(bookId), "$edition:${version.transcriptRevision}"))
                true
            }
        }
        if (!committed) generateLocked(bookId)
    } }

    suspend fun generate(bookId: String): Uri = lock.withLock { generateLocked(bookId) }
    private suspend fun generateLocked(bookId: String): Uri = withContext(Dispatchers.IO) {
        val dao = database.readingDao()
        val book = requireNotNull(database.bookDao().findBook(bookId))
        val previous = requireNotNull(dao.findVersion(bookId)) { "This ReadAlong has no text to save yet." }
        previous.generatedUri?.takeIf { previous.generatedRevision == previous.transcriptRevision && previous.generatedFormat == 1 }?.let { saved ->
            val uri = Uri.parse(saved)
            val readable = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
            if (readable) {
                database.bookDao().putMetadata(LibraryMetadataEntity(liveKey(bookId), "${previous.activeEditionId}:${previous.transcriptRevision}"))
                return@withContext uri
            }
        }
        val partial = !book.isDemo && previous.totalChapters?.let { previous.completedChapters < it } == true
        val revision = previous.transcriptRevision
        val chapters = dao.chapters(bookId)
        check(chapters.isNotEmpty()) { "This ReadAlong has no text to save yet." }
        val audio = database.mediaDao().audioFiles(bookId)
        val embedded = database.mediaDao().chapters(bookId)
        val headings = chapters.mapIndexed { index, chapter -> EpubChapter(chapter.chapterId,
            embedded.find { it.id == chapter.chapterId }?.title ?: audio.find { it.id == chapter.chapterId }?.name ?: "Chapter ${index + 1}") }
        val directory = File(context.filesDir, "editions").apply { mkdirs() }
        val prefix = epubFragment(bookId)
        val edition = UUID.randomUUID().toString()
        val temporary = File(directory, "$prefix-$edition.tmp")
        val destination = File(directory, "$prefix-$edition.epub")
        var published = false
        try {
            // No database write transaction surrounds ZIP creation or XHTML indexing.
            temporary.outputStream().use { output -> EpubWriter.write(output,
                EpubMetadata(bookId, if (book.isDemo) "${book.title} — ReadAlong sample excerpt" else if (partial) "${book.title} — partial transcript" else book.title,
                    if (book.isDemo) "ReadAlong demonstration" else book.author,
                    description = if (book.isDemo) "Original sample prose with synthetic timestamps. No real narration." else if (partial) "Partial generated transcript. Only currently available text is included; later chapters are still being prepared." else "Generated from the timestamped audiobook transcript."), headings) { chapter, offset, limit ->
                ensureActive(); dao.chapterSegments(bookId, chapter, limit, offset).map { it.toSegment() }
            } }
            val indexedSegments = indexGenerated(temporary, bookId, edition)
            ensureActive()
            check(temporary.renameTo(destination)) { "Couldn't save the generated EPUB. Check free storage." }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", destination)
            withContext(NonCancellable) { database.withTransaction {
                val latest = requireNotNull(dao.findVersion(bookId))
                check(latest.transcriptRevision == revision) { "The ReadAlong changed while saving. Please try again." }
                check(indexedSegments == dao.count(bookId)) { "The generated EPUB mapping is incomplete." }
                dao.putVersion(latest.copy(generatedUri = uri.toString(), generatedRevision = revision,
                    activeEditionId = edition, generatedFormat = 1, updatedAt = System.currentTimeMillis()))
                database.bookDao().putMetadata(LibraryMetadataEntity(liveKey(bookId), "$edition:$revision"))
            }; published = true }
            // Publication succeeded. Old derived rows and orphaned files can now be removed.
            withContext(NonCancellable) {
                dao.pruneEditions(bookId, edition)
                dao.clearGenerated(bookId)
                directory.listFiles()?.filter { it.name.startsWith("$prefix-") && it != destination }?.forEach { it.delete() }
                File(directory, "$prefix.epub").delete()
            }
            uri
        } finally {
            withContext(NonCancellable) {
                temporary.delete()
                if (!published) { dao.deleteEdition(bookId, edition); destination.delete() }
            }
        }
    }

    suspend fun export(bookId: String, destination: Uri) = withContext(Dispatchers.IO) {
        val source = generate(bookId)
        context.contentResolver.openInputStream(source).use { input ->
            checkNotNull(input) { "Generated EPUB is unavailable. Try again." }
            context.contentResolver.openOutputStream(destination, "wt").use { output ->
                checkNotNull(output) { "Couldn't write to the selected document." }
                input.copyTo(output)
            }
        }
    }

    private suspend fun indexGenerated(file: File, bookId: String, edition: String): Int {
        var indexed = 0
        val dao = database.readingDao()
        ZipFile(file).use { archive ->
            val entries = archive.entries().asSequence().filter { it.name.matches(Regex("EPUB/chapter-[0-9]+\\.xhtml")) }
            for (entry in entries) {
                coroutineContext.ensureActive()
                archive.getInputStream(entry).use { input ->
                    val parser = Xml.newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                    parser.setInput(input, "UTF-8")
                    val batch = mutableListOf<EpubParagraphEntity>()
                    val text = StringBuilder()
                    var anchors = org.json.JSONArray()
                    var firstId = ""
                    var chapterId = ""
                    var startMs = 0L
                    var endMs = 0L
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "p") {
                            text.clear(); anchors = org.json.JSONArray()
                        } else if (parser.eventType == XmlPullParser.START_TAG && parser.name == "span") {
                            val id = requireNotNull(parser.getAttributeValue(null, "data-segment-id"))
                            check(parser.getAttributeValue(null, "id") == epubFragment(id))
                            val start = requireNotNull(parser.getAttributeValue(null, "data-start-ms")).toLong()
                            endMs = requireNotNull(parser.getAttributeValue(null, "data-end-ms")).toLong()
                            if (anchors.length() == 0) {
                                firstId = id; startMs = start
                                chapterId = requireNotNull(parser.getAttributeValue(null, "data-chapter-id"))
                            } else text.append(' ')
                            val from = text.length
                            text.append(parser.nextText())
                            anchors.put(org.json.JSONObject().put("id", id).put("startMs", start).put("endMs", endMs)
                                .put("start", from).put("end", text.length))
                            indexed++
                        } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "p") {
                            check(anchors.length() > 0)
                            batch += EpubParagraphEntity(bookId, edition, firstId, text.toString(),
                                "${entry.name}#${epubFragment(firstId)}", chapterId, startMs, endMs, anchors.toString())
                            if (batch.size == 80) { coroutineContext.ensureActive(); dao.putEdition(batch.toList()); batch.clear() }
                        }
                    }
                    if (batch.isNotEmpty()) dao.putEdition(batch)
                }
            }
        }
        return indexed
    }
}
