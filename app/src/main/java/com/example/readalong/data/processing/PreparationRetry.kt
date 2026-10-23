package com.example.readalong.data.processing

import androidx.room.withTransaction
import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.data.transcription.SpeechPreparationException
import com.example.readalong.domain.transcription.ProcessingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.FileNotFoundException
import java.io.IOException

/** Retry only temporary failures, with a durable budget per worker/checkpoint. Queue waits do not spend it. */
object PreparationRetry {
    const val MAX_RETRIES = 2
    fun transient(failure: Throwable): Boolean {
        if (failure is TimeoutCancellationException) return true
        if (failure is CancellationException) return false
        val causes = generateSequence(failure) { it.cause }.take(12).toList()
        if (causes.any { it is SecurityException || it is FileNotFoundException || it is IllegalArgumentException ||
                it is com.example.readalong.data.transcription.ModelUnavailableException ||
                it is android.system.ErrnoException && it.errno in listOf(android.system.OsConstants.ENOSPC, android.system.OsConstants.EACCES, android.system.OsConstants.ENOENT) }) return false
        return failure is SpeechPreparationException || causes.any { it is IOException }
    }

    fun explanation(failure: Throwable): String {
        val cause = generateSequence(failure) { it.cause }.take(12).last()
        val stage = (failure as? SpeechPreparationException)?.explanation ?: "Processing failed."
        return "$stage (${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(180)})"
    }
    suspend fun record(database: ReadAlongDatabase, bookId: String, workId: String, failure: Throwable): Boolean = database.withTransaction {
        val job = database.processingDao().find(bookId) ?: return@withTransaction false
        if (job.workId != workId) return@withTransaction false
        database.bookDao().putMetadata(LibraryMetadataEntity("preparation_failure:$bookId", explanation(failure) + "\n" +
            generateSequence(failure) { it.cause }.take(12).joinToString("\n") { cause ->
                cause.javaClass.simpleName + ": " + cause.stackTrace.filter { it.className.startsWith("com.example.readalong") }.take(8).joinToString(" ")
            }))
        if (!ProcessingState.valueOf(job.state).active || !transient(failure)) return@withTransaction false
        val key = "preparation_retry:$bookId"
        val prefix = "$workId:${job.completedUnits}:"
        val old = database.bookDao().metadata(key)
        val count = if (old?.startsWith(prefix) == true) old.removePrefix(prefix).toIntOrNull() ?: 0 else 0
        if (count >= MAX_RETRIES) return@withTransaction false
        database.bookDao().putMetadata(LibraryMetadataEntity(key, "$prefix${count + 1}"))
        database.processingDao().put(job.copy(state = "QUEUED",
            message = "Temporary processing failure. Automatic retry ${count + 1} of $MAX_RETRIES is scheduled; completed text is saved.",
            updatedAt = System.currentTimeMillis()))
        true
    }
}
