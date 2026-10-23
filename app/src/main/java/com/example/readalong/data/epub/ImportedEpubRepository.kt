package com.example.readalong.data.epub

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.*
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.local.*
import com.example.readalong.data.reading.toSegment
import com.example.readalong.domain.alignment.EpubAligner
import com.example.readalong.domain.epub.*
import com.example.readalong.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private const val ALIGNMENT_FORMAT_VERSION = 6

class ImportedEpubRepository(private val context: Context, private val database: ReadAlongDatabase) {
    private val lock = Mutex()
    private val dao = database.importedEpubDao()
    private val manager get() = WorkManager.getInstance(context)
    fun observe(bookId: String) = dao.observe(bookId)

    suspend fun import(bookId: String, uri: Uri) = lock.withLock { withContext(Dispatchers.IO) {
        requireNotNull(database.bookDao().findBook(bookId))
        val directory = File(context.filesDir, "imported-epubs").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val file = File(directory, "$id.epub")
        val fontDirectory = File(directory, "$id-fonts")
        var published = false
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The EPUB could not be opened. Choose it again." }
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var count = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        count += n; require(count <= 128L * 1024 * 1024) { "This EPUB is too large (limit 128 MB)." }; output.write(buffer, 0, n)
                    }
                }
            }
            val job = coroutineContext[Job]
            val parsed = EpubParser().parse(file) { job?.ensureActive() }
            fontDirectory.mkdirs()
            val fontManifest = JSONArray()
            parsed.fonts.forEachIndexed { index, font ->
                coroutineContext.ensureActive()
                val target = File(fontDirectory, "$index.sfnt").apply { writeBytes(font.bytes) }
                val valid = runCatching { android.graphics.Typeface.Builder(target).build() != null }.getOrDefault(false)
                if (valid) fontManifest.put(JSONObject().apply {
                    put("family", font.family); put("weight", font.weight); put("italic", font.italic); put("file", target.name)
                }) else target.delete()
            }
            File(fontDirectory, "manifest.json").writeText(fontManifest.toString())
            val old = dao.find(bookId)
            // Publish the private copy and its complete text atomically. A bad replacement preserves the old edition.
            withContext(NonCancellable) {
                database.withTransaction {
                    database.bookDao().putMetadata(LibraryMetadataEntity("epub_presentation:$id", "2"))
                    database.bookDao().putMetadata(LibraryMetadataEntity("epub_contents:$id", JSONArray().apply {
                    parsed.contents.forEach { put(JSONObject().put("title", it.title).put("ordinal", it.ordinal)) }
                }.toString()))
                parsed.blocks.chunked(200).forEach { batch -> dao.putBlocks(batch.map { block ->
                        ImportedEpubBlockEntity(bookId, id, block.ordinal, block.spine, block.href, block.text, block.kind,
                            encodeStyles(block.styles), block.centered, "[]", -1, -1, 0f)
                    }) }
                    dao.put(ImportedEpubEntity(bookId, id, Uri.fromFile(file).toString(), parsed.title, "QUEUED",
                        "EPUB added. Matching it to the available transcript in the background.", "EPUB", -1, 0, 0, 0, 0, System.currentTimeMillis()))
                    dao.prune(bookId, id)
                }
                published = true
                old?.let { File(Uri.parse(it.uri).path.orEmpty()).takeIf { previous -> previous.parentFile == directory }?.let { previous -> previous.delete(); File(directory, "${old.editionId}-fonts").deleteRecursively() } }
                requestAlignment(bookId)
            }
        } finally { if (!published) { file.delete(); fontDirectory.deleteRecursively() } }
    } }

    suspend fun preference(bookId: String, value: String) {
        require(value in setOf("EPUB", "GENERATED", "AUTO"))
        database.withTransaction { dao.find(bookId)?.let { dao.put(it.copy(preference = value)) } }
    }
    suspend fun remove(bookId: String) = lock.withLock {
        val old = dao.find(bookId) ?: return@withLock
        withContext(NonCancellable + Dispatchers.IO) {
            database.withTransaction {
                dao.remove(bookId); dao.clearBlocks(bookId)
                database.bookDao().removeMetadata("epub_contents:${old.editionId}")
                database.bookDao().removeMetadata("epub_presentation:${old.editionId}")
            }
            manager.cancelUniqueWork("epub-alignment:$bookId").result.get()
            val file = File(Uri.parse(old.uri).path.orEmpty())
            if (file.parentFile == File(context.filesDir, "imported-epubs")) { file.delete(); File(file.parentFile, "${old.editionId}-fonts").deleteRecursively() }
        }
    }
    /** A draft is tied to the exact edition and audio layout, never to a stale screen. */
    suspend fun manualDraft(bookId: String, editionId: String, ordinal: Int, audioMs: Long): ManualSyncDraft = database.withTransaction {
        val edition = requireNotNull(dao.find(bookId))
        require(edition.editionId == editionId && edition.state != "AUDIO_CHANGED") { "The edition or audio changed. Reopen the passage." }
        val block = requireNotNull(dao.block(bookId, editionId, ordinal))
        val duration = requireNotNull(database.bookDao().findBook(bookId)?.durationMs) { "Audio duration is not available yet." }
        require(duration > 0 && audioMs in 0 until duration) { "Choose a position within this audiobook." }
        val end = database.readingDao().at(bookId, audioMs)?.endMs ?: (audioMs + 10_000).coerceAtMost(duration)
        ManualSyncDraft(bookId, editionId, ordinal, block.text, sourceKey(bookId),
            if (block.manualStartMs >= 0) block.manualStartMs else audioMs,
            if (block.manualStartMs >= 0) block.manualEndMs else end.coerceAtMost(duration), duration, block.manualStartMs >= 0)
    }

    suspend fun saveManual(draft: ManualSyncDraft, startMs: Long, endMs: Long, reset: Boolean = false) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val edition = requireNotNull(dao.find(draft.bookId)) { "The EPUB was removed." }
            require(edition.editionId == draft.editionId && edition.state != "AUDIO_CHANGED" && sourceKey(draft.bookId) == draft.sourceKey) {
                "The edition or audio changed. Reopen the passage before setting sync."
            }
            val blocks = dao.blocks(draft.bookId, draft.editionId)
            val selected = requireNotNull(blocks.find { it.ordinal == draft.ordinal })
            require(selected.text == draft.text) { "The passage changed. Reopen it before setting sync." }
            if (!reset) {
                val duration = database.bookDao().findBook(draft.bookId)?.durationMs ?: 0
                require(startMs >= 0 && endMs > startMs && endMs <= duration) { "End must follow start and stay within the audiobook." }
                require(blocks.none { it.ordinal != draft.ordinal && it.manualStartMs >= 0 && startMs < it.manualEndMs && endMs > it.manualStartMs }) {
                    "Another manual sync uses this audio interval. Reset that passage first, or choose a different interval."
                }
            }
            val updated = blocks.map { block -> block.copy(
                automaticAnchorsJson = block.automaticAnchorsJson ?: block.anchorsJson,
                automaticConfidence = block.automaticConfidence ?: block.confidence,
                manualStartMs = if (block.ordinal == draft.ordinal) { if (reset) -1 else startMs } else block.manualStartMs,
                manualEndMs = if (block.ordinal == draft.ordinal) { if (reset) -1 else endMs } else block.manualEndMs) }
            publishManualOverlay(updated)
            dao.put(edition.copy(manualCount = updated.count { it.manualStartMs >= 0 }, updatedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun sourceKey(bookId: String): String =
        database.mediaDao().audioFiles(bookId).toString() + database.mediaDao().chapters(bookId).toString()

    /** Keep automatic estimates separately: resets are immediate, and later alignment cannot erase manual choices. */
    private suspend fun publishManualOverlay(blocks: List<ImportedEpubBlockEntity>) {
        val manual = blocks.filter { it.manualStartMs >= 0 }
        val updated = blocks.map { block ->
            val anchors = if (block.manualStartMs >= 0) listOf(TranscriptAnchor(
                "MANUAL_ALIGNMENT:${block.editionId}:${block.ordinal}", block.manualStartMs, block.manualEndMs, 0, block.text.length))
            else block.copy(anchorsJson = block.automaticAnchorsJson ?: block.anchorsJson).toReadingSegment().anchors.filterNot { it.estimated }.filter { anchor ->
                manual.none { anchor.startMs < it.manualEndMs && anchor.endMs > it.manualStartMs }
            }
            block.copy(anchorsJson = encodeAnchors(anchors), startMs = anchors.minOfOrNull { it.startMs } ?: -1,
                endMs = anchors.maxOfOrNull { it.endMs } ?: -1,
                confidence = if (block.manualStartMs >= 0) 1f else block.automaticConfidence ?: block.confidence)
        }
        val paragraphs = updated.map { it.toReadingSegment() }
        val cues = paragraphs.chunked(40).flatMap { batch ->
            val ids = batch.flatMap { it.anchors }.filterNot { it.estimated }.map { it.id }.distinct()
            val sources = ids.chunked(400).flatMap { database.readingDao().sourceSegments(blocks.first().bookId, it) }
                .associate { it.id to it.toSegment() }
            batch.map { com.example.readalong.domain.reading.sentenceCues(it, sources) }
        }
        val estimates = com.example.readalong.domain.reading.estimatedGapAnchors(paragraphs, cues)
        val saved = blocks.firstOrNull()?.let { dao.blocks(it.bookId, it.editionId).associateBy { b -> b.ordinal } }.orEmpty()
        updated.mapIndexed { i, block ->
            val anchors = paragraphs[i].anchors + estimates[paragraphs[i].id].orEmpty()
            block.copy(anchorsJson = encodeAnchors(anchors), startMs = anchors.minOfOrNull { it.startMs } ?: -1,
                endMs = anchors.maxOfOrNull { it.endMs } ?: -1)
        }.filter { it != saved[it.ordinal] }.chunked(200).forEach { dao.putBlocks(it) }
    }

    /** Load only this edition's validated private fonts, away from the UI thread. Legacy editions fall back. */
    suspend fun fontFamilies(editionId: String): Map<String, androidx.compose.ui.text.font.FontFamily> = withContext(Dispatchers.IO) {
        if (!editionId.matches(Regex("[a-fA-F0-9-]{36}"))) return@withContext emptyMap()
        val directory = File(context.filesDir, "imported-epubs/$editionId-fonts")
        val manifest = File(directory, "manifest.json")
        if (!manifest.isFile || manifest.length() > 32_768) return@withContext emptyMap()
        runCatching {
            val json = JSONArray(manifest.readText())
            (0 until minOf(json.length(), 24)).mapNotNull { i ->
                val entry = json.getJSONObject(i)
                val name = entry.getString("file")
                if (!name.matches(Regex("[0-9]+\\.sfnt"))) return@mapNotNull null
                val file = File(directory, name)
                if (!file.isFile || android.graphics.Typeface.Builder(file).build() == null) return@mapNotNull null
                Triple(entry.getString("family"), file, entry)
            }.groupBy { it.first }.mapValues { (_, variants) ->
                // Keep loaded typefaces in memory: replacing an EPUB cannot race a later file-font load.
                val typeface = if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val loaded = variants.distinctBy { it.third.getInt("weight") to it.third.optBoolean("italic") }.mapNotNull { (_, file, entry) ->
                        runCatching { android.graphics.fonts.Font.Builder(file)
                            .setWeight(entry.getInt("weight").coerceIn(100, 900))
                            .setSlant(if (entry.optBoolean("italic")) android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC else android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT).build() }.getOrNull()
                    }
                    if (loaded.isEmpty()) return@mapValues androidx.compose.ui.text.font.FontFamily.Serif
                    val family = android.graphics.fonts.FontFamily.Builder(loaded.first())
                    loaded.drop(1).forEach { family.addFont(it) }
                    android.graphics.Typeface.CustomFallbackBuilder(family.build()).setSystemFallback("serif").build()
                } else {
                    val regular = variants.minBy { kotlin.math.abs(it.third.getInt("weight") - 400) + if (it.third.optBoolean("italic")) 1000 else 0 }
                    android.graphics.Typeface.Builder(regular.second).build() ?: android.graphics.Typeface.SERIF
                }
                androidx.compose.ui.text.font.FontFamily(typeface)
            }
        }.getOrDefault(emptyMap())
    }

    /** Append before publication's worker ends, so a request cannot be lost in its completion window. */
    private val alignmentScheduling = Mutex()
    suspend fun requestAlignment(bookId: String) = alignmentScheduling.withLock { withContext(Dispatchers.IO) {
        val edition = dao.find(bookId) ?: return@withContext
        val revision = database.readingDao().findVersion(bookId)?.transcriptRevision ?: 0
        if (database.bookDao().metadata("epub_alignment_version:$bookId") == "${edition.editionId}:$ALIGNMENT_FORMAT_VERSION" && edition.alignedRevision == revision && edition.state !in setOf("FAILED", "QUEUED", "ALIGNING")) return@withContext
        // Coalesce checkpoints: one running alignment and at most one successor, rather than thousands of queued copies.
        val pending = manager.getWorkInfosForUniqueWork("epub-alignment:$bookId").get().count { !it.state.isFinished }
        if (pending >= 2) return@withContext
        val work = OneTimeWorkRequestBuilder<EpubAlignmentWorker>().setInputData(workDataOf("bookId" to bookId))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).addTag("epub-alignment").build()
        manager.enqueueUniqueWork("epub-alignment:$bookId", ExistingWorkPolicy.APPEND_OR_REPLACE, work).result.get()
    } }
    internal suspend fun alignmentDelayMs(bookId: String, now: Long = System.currentTimeMillis()): Long {
        val last = database.bookDao().metadata("epub_alignment_published:$bookId")?.toLongOrNull() ?: return 0
        return (30_000L - (now - last)).coerceIn(0, 30_000L)
    }
    suspend fun recover() { dao.all().forEach { edition ->
        try { upgradeContents(edition.bookId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { android.util.Log.w("ReadAlong", "EPUB contents will retry when the book opens.") }
        requestAlignment(edition.bookId)
    } }
    /** Re-index existing private EPUBs without running recognition or discarding manual timing. */
    suspend fun upgradeContents(bookId: String) = lock.withLock { withContext(Dispatchers.IO) {
        val edition = dao.find(bookId) ?: return@withContext
        val key = "epub_contents:${edition.editionId}"
        if (database.bookDao().metadata("epub_presentation:${edition.editionId}") == "2") return@withContext
        val source = File(Uri.parse(edition.uri).path.orEmpty())
        val parsed = EpubParser().parse(source) { ensureActive() }
        database.withTransaction {
            if (dao.find(bookId)?.editionId != edition.editionId) return@withTransaction
            val previous = dao.blocks(bookId, edition.editionId)
            val byText = previous.groupBy { it.href.substringBefore('#') to it.text }.mapValues { it.value.toMutableList() }
            val migratedOrdinals = mutableMapOf<Int, Int>()
            val newBlocks = parsed.blocks.map { block ->
                val old = byText[block.href.substringBefore('#') to block.text]?.let { if (it.isEmpty()) null else it.removeAt(0) }
                if (old != null) migratedOrdinals[old.ordinal] = block.ordinal
                old?.copy(ordinal = block.ordinal, spine = block.spine, href = block.href, kind = block.kind, stylesJson = encodeStyles(block.styles), centered = block.centered)
                    ?: ImportedEpubBlockEntity(bookId, edition.editionId, block.ordinal, block.spine, block.href, block.text, block.kind, encodeStyles(block.styles), block.centered, "[]", -1, -1, 0f)
            }
            val progress = database.readingDao().progress(bookId)
            if (progress?.epubEditionId == edition.editionId && progress.epubOrdinal != null) {
                val ordinal = migratedOrdinals[progress.epubOrdinal]
                    ?: migratedOrdinals.keys.filter { it > progress.epubOrdinal }.minOrNull()?.let(migratedOrdinals::get)
                    ?: newBlocks.lastOrNull()?.ordinal
                if (ordinal != null) database.readingDao().putProgress(progress.copy(epubOrdinal = ordinal,
                    segmentId = if (progress.segmentId.startsWith("epub:")) "epub:${edition.editionId}:$ordinal" else progress.segmentId))
            }
            dao.put(edition.copy(alignedRevision = -1, updatedAt = System.currentTimeMillis()))
            dao.clearBlocks(bookId); newBlocks.chunked(200).forEach { dao.putBlocks(it) }
            database.bookDao().putMetadata(LibraryMetadataEntity("epub_presentation:${edition.editionId}", "2"))
            database.bookDao().putMetadata(LibraryMetadataEntity(key, JSONArray().apply {
                parsed.contents.forEach { put(JSONObject().put("title", it.title).put("ordinal", it.ordinal)) }
            }.toString()))
        }
    } }
    suspend fun retry(bookId: String) {
        database.withTransaction {
            dao.find(bookId)?.takeIf { it.state != "AUDIO_CHANGED" }?.let {
                dao.put(it.copy(state = "QUEUED", alignedRevision = -1, message = "EPUB matching queued for retry."))
            }
        }
        requestAlignment(bookId)
    }

    /** The transcript is snapshotted without word JSON; imported text never writes into canonical segments. */
    suspend fun align(bookId: String): Boolean = withContext(Dispatchers.Default) {
        val initial = dao.find(bookId) ?: return@withContext true
        if (initial.state == "AUDIO_CHANGED") return@withContext true
        if (database.bookDao().metadata("epub_alignment_version:$bookId") == "${initial.editionId}:$ALIGNMENT_FORMAT_VERSION" && initial.alignedRevision == (database.readingDao().findVersion(bookId)?.transcriptRevision ?: 0L) && initial.state !in setOf("FAILED", "QUEUED", "ALIGNING")) return@withContext true
        var snapshotWorkId: String? = null
        val snapshot = database.withTransaction {
            snapshotWorkId = database.processingDao().find(bookId)?.workId
            val version = database.readingDao().findVersion(bookId)
            val rows = mutableListOf<TranscriptSegment>(); var offset = 0
            while (true) {
                val page = database.readingDao().alignmentSegments(bookId, 500, offset)
                if (page.isEmpty()) break
                rows += page.map { TranscriptSegment(it.id, it.bookId, it.chapterId, it.startMs, it.endMs, it.text, it.confidence) }
                offset += page.size
            }
            Pair(version?.transcriptRevision ?: 0L, rows)
        }
        val blocks = dao.blocks(bookId, initial.editionId)
        database.withTransaction {
            dao.find(bookId)?.takeIf { it.editionId == initial.editionId && it.state != "AUDIO_CHANGED" }?.let { dao.put(it.copy(state = "ALIGNING", message = "Matching EPUB passages to the saved audio timestamps…")) }
        }
        val job = coroutineContext[Job]
        val result = if (snapshot.second.isEmpty()) com.example.readalong.domain.alignment.AlignmentResult(
            blocks.map { com.example.readalong.domain.alignment.AlignedBlock(it.toBlock(), emptyList(), 0f) }, 0, 0, 0, 0)
        else EpubAligner().align(blocks.map { it.toBlock() }, snapshot.second) { job?.ensureActive() }
        // Load word timing in small batches, not for the entire audiobook at once.
        val precise = mutableMapOf<Int, List<TranscriptAnchor>>()
        result.blocks.chunked(40).forEach { batch ->
            val ids = batch.flatMap { it.anchors }.map { it.id }.distinct()
            val sources = ids.chunked(400).flatMap { database.readingDao().sourceSegments(bookId, it) }
                .associate { it.id to it.toSegment() }
            batch.forEach { block ->
                precise[block.block.ordinal] = block.anchors.mapNotNull { anchor ->
                    val source = sources[anchor.id]
                    val words = com.example.readalong.domain.reading.timedAnchorWords(block.block.text, anchor, source).map { it.second }
                    if (words.isNotEmpty()) anchor.copy(startMs = words.minOf { it.startMs }, endMs = words.maxOf { it.endMs })
                        .takeIf { it.endMs > it.startMs }
                    else anchor.takeIf { source?.words.isNullOrEmpty() }
                }
            }
            coroutineContext.ensureActive()
        }
        coroutineContext.ensureActive()
        database.withTransaction {
            val latest = dao.find(bookId) ?: return@withTransaction true
            if (latest.state == "AUDIO_CHANGED") return@withTransaction true
            if (latest.editionId != initial.editionId) return@withTransaction false
            if ((database.readingDao().findVersion(bookId)?.transcriptRevision ?: 0L) != snapshot.first) {
                // A newer appended chunk must not starve publication of an already useful snapshot.
                // Reject replacements/corrections/rebuilds; allow only the identical saved prefix.
                if (database.processingDao().find(bookId)?.workId != snapshotWorkId) return@withTransaction false
                val current = database.readingDao().alignmentSegments(bookId, snapshot.second.size, 0)
                    .map { TranscriptSegment(it.id, it.bookId, it.chapterId, it.startMs, it.endMs, it.text, it.confidence) }
                if (current != snapshot.second) return@withTransaction false
            }
            // Re-read inside publication's transaction: the user may have saved a sync point while alignment ran.
            val currentBlocks = dao.blocks(bookId, initial.editionId).associateBy { it.ordinal }
            publishManualOverlay(result.blocks.map { aligned ->
                requireNotNull(currentBlocks[aligned.block.ordinal]).copy(
                    automaticAnchorsJson = encodeAnchors(precise[aligned.block.ordinal].orEmpty()), automaticConfidence = aligned.confidence)
            })
            val state = when {
                snapshot.second.isEmpty() -> "WAITING_TRANSCRIPT"
                result.coverage >= .85f && result.useful -> "ALIGNED"
                result.matchedSegments > 0 -> "PARTIAL"
                else -> "UNUSABLE"
            }
            val message = when (state) {
                "WAITING_TRANSCRIPT" -> "EPUB ready to read. Audio sync will update as Whistle prepares the whole book."
                "UNUSABLE" -> "No reliable matches yet. Check that this is the same book and edition. Generated text remains available."
                else -> "${(result.coverage * 100).toInt()}% of available transcript words matched · ${result.matchedSegments}/${result.totalSegments} passages. Short unmatched gaps between timed passages use estimated timing."
            }
            database.bookDao().putMetadata(LibraryMetadataEntity("epub_alignment_version:$bookId", "${initial.editionId}:$ALIGNMENT_FORMAT_VERSION"))
            if (snapshot.second.isNotEmpty()) database.bookDao().putMetadata(LibraryMetadataEntity("epub_alignment_published:$bookId", System.currentTimeMillis().toString()))
            dao.put(latest.copy(state = state, message = message, alignedRevision = snapshot.first, matchedSegments = result.matchedSegments,
                totalSegments = result.totalSegments, matchedTokens = result.matchedTokens, totalTokens = result.totalTokens, updatedAt = System.currentTimeMillis()))
            true
        }
    }
    suspend fun failed(bookId: String, editionId: String?, message: String) = database.withTransaction {
        dao.find(bookId)?.takeIf { it.editionId == editionId && it.state != "AUDIO_CHANGED" }?.let { dao.put(it.copy(state = "FAILED", message = message)) }
    }
}

class EpubAlignmentWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ReadAlongApplication
        val bookId = inputData.getString("bookId") ?: return Result.failure()
        val editionId = app.database.importedEpubDao().find(bookId)?.editionId
        if (!alignment.tryLock()) return Result.retry()
        return try {
            delay(app.importedEpub.alignmentDelayMs(bookId))
            if (withTimeout(8 * 60_000L) { app.importedEpub.align(bookId) }) {
                // The last checkpoint can arrive during computation or publication.
                app.importedEpub.requestAlignment(bookId)
                Result.success()
            } else Result.retry()
        } catch (_: TimeoutCancellationException) { Result.retry() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            app.importedEpub.failed(bookId, editionId, failure.message ?: "EPUB alignment failed. The generated text and audio remain available.")
            Result.failure()
        } finally { alignment.unlock() }
    }
    companion object { private val alignment = Mutex() }
}

fun ImportedEpubEntity.useForReading(): Boolean = preference != "GENERATED"

fun ImportedEpubBlockEntity.toBlock() = EpubBlock(ordinal, spine, href, text, kind, decodeStyles(stylesJson), centered)
fun ImportedEpubBlockEntity.toReadingSegment(): TranscriptSegment = TranscriptEntity("epub:$editionId:$ordinal", bookId, "epub:$spine", startMs, endMs, text, confidence, null, anchorsJson).toSegment().copy(
    formatting = PassageFormatting(kind, decodeStyles(stylesJson), centered), imported = true)
private fun encodeStyles(styles: List<EpubTextStyle>) = JSONArray().apply { styles.forEach { s -> put(JSONObject().apply {
    put("start", s.start); put("end", s.end); put("bold", s.bold); put("italic", s.italic); put("underline", s.underline); s.fontFamily?.let { put("fontFamily", it) }
}) } }.toString()
private fun decodeStyles(json: String): List<EpubTextStyle> = JSONArray(json).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let {
    EpubTextStyle(it.getInt("start"), it.getInt("end"), it.optBoolean("bold"), it.optBoolean("italic"), it.optBoolean("underline"), it.optString("fontFamily").takeIf { family -> family.isNotBlank() })
} } }
private fun encodeAnchors(anchors: List<TranscriptAnchor>) = JSONArray().apply { anchors.forEach { a -> put(JSONObject().apply {
    put("id", a.id); put("startMs", a.startMs); put("endMs", a.endMs); put("start", a.start); put("end", a.end)
    a.sourceStart?.let { put("sourceStart", it) }; a.sourceEnd?.let { put("sourceEnd", it) }
    if (a.estimated) put("estimated", true)
}) } }.toString()

data class ManualSyncDraft(val bookId: String, val editionId: String, val ordinal: Int, val text: String,
    val sourceKey: String, val startMs: Long, val endMs: Long, val durationMs: Long, val existing: Boolean)
