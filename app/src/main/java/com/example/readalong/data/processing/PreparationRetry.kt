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
    /** Delays before automatic retries 1, 2 and 3. Each retry starts a fresh recognition session. */
    val DELAYS_MS = longArrayOf(5_000, 20_000, 60_000)
    val MAX_RETRIES = DELAYS_MS.size

    sealed interface Decision {
        /** Retry [attempt] (1-based) after [delayMs]. */
        data class Retry(val attempt: Int, val delayMs: Long) : Decision
        /** Every automatic retry for this checkpoint failed. */
        data object Exhausted : Decision
        /** Not a temporary failure, or this run no longer owns the book. */
        data object NotRetryable : Decision
    }
    fun transient(failure: Throwable): Boolean {
        if (failure is TimeoutCancellationException) return true
        if (failure is CancellationException) return false
        val causes = generateSequence(failure) { it.cause }.take(12).toList()
        if (causes.any { it is SecurityException || it is FileNotFoundException || it is IllegalArgumentException ||
                it is com.example.readalong.data.transcription.ModelUnavailableException ||
                it is android.system.ErrnoException && it.errno in listOf(android.system.OsConstants.ENOSPC, android.system.OsConstants.EACCES, android.system.OsConstants.ENOENT) }) return false
        // A recognition process that died mid-call surfaces as DeadObjectException (a RemoteException).
        return failure is SpeechPreparationException || causes.any { it is IOException || it is android.os.RemoteException }
    }

    fun explanation(failure: Throwable): String {
        val cause = generateSequence(failure) { it.cause }.take(12).last()
        val stage = (failure as? SpeechPreparationException)?.explanation ?: "Processing failed."
        return "$stage (${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(180)})"
    }
    suspend fun record(database: ReadAlongDatabase, bookId: String, workId: String, failure: Throwable): Boolean =
        decide(database, bookId, workId, failure) is Decision.Retry

    suspend fun decide(database: ReadAlongDatabase, bookId: String, workId: String, failure: Throwable): Decision = database.withTransaction {
        val job = database.processingDao().find(bookId) ?: return@withTransaction Decision.NotRetryable
        if (job.workId != workId) return@withTransaction Decision.NotRetryable
        database.bookDao().putMetadata(LibraryMetadataEntity("preparation_failure:$bookId", explanation(failure) + "\n" +
            generateSequence(failure) { it.cause }.take(12).joinToString("\n") { cause ->
                cause.javaClass.simpleName + ": " + cause.stackTrace.filter { it.className.startsWith("com.example.readalong") }.take(8).joinToString(" ")
            }))
        if (!ProcessingState.valueOf(job.state).active || !transient(failure)) return@withTransaction Decision.NotRetryable
        val key = "preparation_retry:$bookId"
        val prefix = "$workId:${job.completedUnits}:"
        val old = database.bookDao().metadata(key)
        val count = if (old?.startsWith(prefix) == true) old.removePrefix(prefix).toIntOrNull() ?: 0 else 0
        if (count >= MAX_RETRIES) return@withTransaction Decision.Exhausted
        val delayMs = DELAYS_MS[count]
        database.bookDao().putMetadata(LibraryMetadataEntity(key, "$prefix${count + 1}"))
        database.processingDao().put(job.copy(state = "QUEUED",
            message = "Temporary problem. Retrying automatically in ${delayMs / 1000} s (attempt ${count + 1} of $MAX_RETRIES). Completed text is saved.",
            updatedAt = System.currentTimeMillis()))
        Decision.Retry(count + 1, delayMs)
    }
}
