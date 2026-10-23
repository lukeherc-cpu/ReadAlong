package com.example.readalong.data.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.readalong.ReadAlongApplication
import com.example.readalong.domain.transcription.ProcessingState

/** Lightweight durable guard for directly started foreground runs; never authorizes an idle book. */
class PreparationRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val book = inputData.getString("bookId") ?: return Result.success()
        val expected = inputData.getString("workId") ?: return Result.success()
        val app = applicationContext as ReadAlongApplication
        val job = app.database.processingDao().find(book) ?: return Result.success()
        if (job.workId != expected || !ProcessingState.valueOf(job.state).active) return Result.success()
        return try {
            if (AudiobookTranscriptionService.running(book, expected)) app.processing.guardForeground(book, expected, append = true)
            else app.processing.recoverInterrupted(book, expected)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}
