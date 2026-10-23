package com.example.readalong.data.processing

import androidx.room.withTransaction
import com.example.readalong.data.epub.GeneratedEpubRepository
import com.example.readalong.data.local.*
import com.example.readalong.data.reading.DemoTranscript
import com.example.readalong.data.reading.toEntity
import com.example.readalong.domain.model.TranscriptIndex
import com.example.readalong.domain.transcription.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import kotlin.coroutines.coroutineContext

/** Checkpoints each bounded unit. A cancelled or superseded run cannot commit late results. */
class ProcessingRunner(private val database: ReadAlongDatabase, private val epub: GeneratedEpubRepository) {
    suspend fun run(bookId: String, workId: String, engine: TranscriptionEngine = SampleTranscriptionEngine(), maxUnits: Int = Int.MAX_VALUE,
        afterCheckpoint: suspend () -> Boolean = { true }): Boolean {
        val jobs = database.processingDao()
        try {
            val book = requireNotNull(database.bookDao().findBook(bookId))
            val initial = database.withTransaction {
                val job = current(bookId, workId)
                if (job.planJson != null) job else job.copy(planJson = TranscriptionPlan.encode(TranscriptionPlan.inputs(database, bookId)),
                    sourceFingerprint = TranscriptionPlan.fingerprint(database, bookId)).also { jobs.put(it) }
            }
            val units = TranscriptionPlan.decode(requireNotNull(initial.planJson))
            if (initial.sourceFingerprint != TranscriptionPlan.fingerprint(database, bookId)) throw AudioLayoutChanged()
            // A process may stop after the atomic transcript/checkpoint commit but before
            // publishing its reading edition. Repair that derived view without recognizing
            // any already completed audio again. Normally this is a cheap revision check.
            if (initial.completedUnits > 0) epub.refreshReading(bookId)
            val chapterEnds = units.withIndex().groupBy { it.value.chapterId }.mapValues { (_, entries) -> entries.last().index }
            var processed = 0
            require((book.isDemo && engine.id == "sample-v1") || (!book.isDemo && engine.id == initial.engineId))
            if (initial.completedUnits == 0 && engine.id == com.example.readalong.data.transcription.SpeechModelStore.ID) {
                database.bookDao().putMetadata(LibraryMetadataEntity(com.example.readalong.data.audio.Mp3Timing.versionKey(bookId),
                    com.example.readalong.data.audio.Mp3Timing.VERSION))
            }
            for ((index, input) in units.withIndex()) {
                val job = current(bookId, workId)
                if (index < job.completedUnits) continue
                update(bookId, workId, ProcessingState.PROCESSING, if (book.isDemo) "Preparing sample chapter ${index + 1} of ${units.size}." else "Transcribing chunk ${index + 1} of ${units.size} with ${com.example.readalong.domain.transcription.SpeechModels.find(engine.id)?.name ?: "Offline speech model"}…")
                val unitBegan = android.os.SystemClock.elapsedRealtime()
                val segments = engine.transcribe(input).toList() // A real unit contains at most 30 seconds of audio.
                coroutineContext.ensureActive()
                if (book.isDemo) check(segments.isNotEmpty()) { "No readable text was produced." }
                TranscriptIndex(segments) // Validates canonical interval ordering before committing.
                check(segments.all { it.bookId == bookId && it.chapterId == input.chapterId && it.startMs >= input.startMs && it.endMs <= input.endMs })
                database.withTransaction {
                    val latest = current(bookId, workId)
                    if (latest.sourceFingerprint != TranscriptionPlan.fingerprint(database, bookId)) throw AudioLayoutChanged()
                    val reading = database.readingDao()
                    val version = reading.findVersion(bookId) ?: ReadableVersionEntity(bookId, "PARTIAL_READY", null, 0, chapterEnds.size, null, 0)
                    if (!book.isDemo) reading.deleteRange(bookId, input.startMs, input.endMs)
                    reading.putSegments(segments.map { it.toEntity() })
                    reading.putVersion(version.copy(state = "PARTIAL_READY", completedChapters = chapterEnds.values.count { it <= index }, totalChapters = chapterEnds.size,
                        message = "${chapterEnds.values.count { it <= index }} of ${chapterEnds.size} chapters prepared. You can listen everywhere and read available text.",
                        transcriptRevision = version.transcriptRevision + 1, updatedAt = System.currentTimeMillis()))
                    jobs.put(latest.copy(state = ProcessingState.PARTIAL_READY.name, completedUnits = index + 1,
                        message = if (book.isDemo) "${index + 1} of ${units.size} sample chapters prepared." else "${index + 1} of ${units.size} chunks transcribed.", updatedAt = System.currentTimeMillis()))
                }
                val publishBegan = android.os.SystemClock.elapsedRealtime()
                epub.publishReadingRange(input)
                android.util.Log.d("ReadAlongPerf", "reading_publish_ms=${android.os.SystemClock.elapsedRealtime() - publishBegan}")
                // Persist a rolling rate beside the checkpoint; no recognition timestamps are changed.
                if (!book.isDemo) try {
                    TranscriptionEstimate.record(database, job, input.endMs - input.startMs,
                        android.os.SystemClock.elapsedRealtime() - unitBegan)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Estimates are optional; never fail a saved transcription. */ }
                processed++
                val continueSession = afterCheckpoint()
                if ((processed >= maxUnits || !continueSession) && index < units.lastIndex) {
                    // Live paragraphs were published above. Build the ZIP only at completion
                    // or explicit export, avoiding repeated whole-book indexing on the hot path.
                    return false
                }
            }
            if (database.readingDao().count(bookId) == 0) {
                update(bookId, workId, ProcessingState.READY, "Processing finished, but no speech was recognized. Audio remains available.")
            } else {
                update(bookId, workId, ProcessingState.GENERATING_EPUB, if (book.isDemo) "Building the sample EPUB." else "Building the readable EPUB.")
                epub.generate(bookId)
                update(bookId, workId, ProcessingState.READY,
                    if (book.isDemo) "Sample readable version ready · synthetic timestamps · no narration." else "Whole-book readable version ready · ${com.example.readalong.domain.transcription.SpeechModels.find(engine.id)?.name ?: "Offline speech model"} · recognition may contain errors.")
            }
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            android.util.Log.w("ReadAlong", "Readable pipeline failed: ${failure.javaClass.simpleName}")
            database.withTransaction {
                val latest = jobs.find(bookId)
                if (latest?.workId == workId && ProcessingState.valueOf(latest.state).active) {
                    if (failure is AudioLayoutChanged) database.importedEpubDao().invalidateAudioMapping(bookId)
                    jobs.put(latest.copy(state = if (PreparationRetry.transient(failure)) ProcessingState.QUEUED.name else ProcessingState.FAILED.name,
                        message = if (failure is AudioLayoutChanged) "The audio file order, duration or chapter boundaries changed. Retry preparation to build a new plan. The saved edition and listening position are preserved." else if (failure is com.example.readalong.data.transcription.SpeechPreparationException) failure.explanation
                        else "Couldn't prepare readable text. Check model, audio access and free storage, then retry. Completed text, audio and saved positions are preserved.", updatedAt = System.currentTimeMillis()))
                }
            }
            throw failure
        }
    }
    private suspend fun current(bookId: String, workId: String): ProcessingJobEntity {
        val job = database.processingDao().find(bookId)
        if (job?.workId != workId || !ProcessingState.valueOf(job.state).active) throw CancellationException("Run cancelled or superseded")
        return job
    }
    private suspend fun update(bookId: String, workId: String, state: ProcessingState, message: String) = database.withTransaction {
        val latest = current(bookId, workId)
        database.processingDao().put(latest.copy(state = state.name, message = message, updatedAt = System.currentTimeMillis()))
        if (state == ProcessingState.READY) database.readingDao().findVersion(bookId)?.let { version ->
            database.readingDao().putVersion(version.copy(state = if (database.bookDao().findBook(bookId)?.isDemo == true) "DEMO_READY" else "READY", message = message, updatedAt = System.currentTimeMillis()))
        }
        if (state == ProcessingState.PREVIEW_READY) database.readingDao().findVersion(bookId)?.let { version ->
            database.readingDao().putVersion(version.copy(state = "PREVIEW_READY", message = message, updatedAt = System.currentTimeMillis()))
        }
    }
}

private class AudioLayoutChanged : IllegalStateException()
