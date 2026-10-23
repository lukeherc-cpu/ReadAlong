package com.example.readalong.data.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.room.withTransaction
import com.example.readalong.ReadAlongApplication
import com.example.readalong.domain.transcription.ProcessingState
import kotlinx.coroutines.*

/** Durable scheduling bridge. Real recognition always belongs to the foreground service. */
class ReadableVersionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val bookId = inputData.getString("bookId") ?: return Result.failure()
        val app = applicationContext as ReadAlongApplication
        val current = app.database.processingDao().find(bookId) ?: return Result.success()
        if (current.workId != id.toString() || !ProcessingState.valueOf(current.state).active) return Result.success()
        // Avoid several books loading/decoding concurrently or spending their job budgets waiting for inference.
        if (AudiobookTranscriptionService.running(bookId, id.toString())) return Result.success()
        val result = try {
            val isDemo = app.database.bookDao().findBook(bookId)?.isDemo == true
            if (!isDemo && !app.processing.isTurn(bookId, id.toString())) return Result.retry()
            val resources = TranscriptionResources(app)
            if (!isDemo && ChargingPolicy.shouldWait(app)) {
                app.processing.waitForCharging(bookId, id.toString())
                return Result.success()
            }
            if (!isDemo) return restoreForegroundOwner(app, bookId)
            var alignmentRequested = false
            suspend fun alignCheckpoint() {
                alignmentRequested = true
                // Matching remains live after every commit, including inside a batch.
                try { app.importedEpub.requestAlignment(bookId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { android.util.Log.w("ReadAlong", "EPUB alignment will retry on the next checkpoint or app launch.") }
            }
            val complete = withTimeout(9 * 60_000L) {
                val runner = ProcessingRunner(app.database, app.generatedEpub)
                runner.run(bookId, id.toString(), SampleTranscriptionEngine())
            }

            // Alignment uses committed text only and never calls the speech model. Keep its failures isolated.
            val latest = app.database.processingDao().find(bookId)
            if (!alignmentRequested && (latest?.completedUnits ?: 0) > 0) {
                alignCheckpoint()
            }
            if (!complete) app.processing.continuePreparation(bookId, id.toString(), if (resources.warm()) 30_000 else 0)
            Result.success()
        } catch (failure: TimeoutCancellationException) {
            if (PreparationRetry.record(app.database, bookId, id.toString(), failure)) Result.retry() else {
                fail(app, bookId, "This audio chunk exceeded the processing time limit. Completed text is saved; retry when the device is idle.")
                Result.failure()
            }
        } catch (cancelled: CancellationException) {
            // Keep both classification and persistence inside NonCancellable. Returning a value
            // from an IO block to the cancelled worker can throw before the state is saved.
            withContext(NonCancellable + Dispatchers.IO) {
                val cancelledByApp = (android.os.Build.VERSION.SDK_INT >= 31 && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) ||
                    WorkManager.getInstance(applicationContext).getWorkInfoById(id).get()?.state == WorkInfo.State.CANCELLED
                if (cancelledByApp && !ChargingPolicy.shouldWait(app)) app.database.withTransaction {
                    val job = app.database.processingDao().find(bookId)
                    if (job?.workId == id.toString() && ProcessingState.valueOf(job.state).active) {
                        app.database.processingDao().put(job.copy(state = "CANCELLED",
                            message = "Preparation paused. Completed text, audio and saved positions remain available.", updatedAt = System.currentTimeMillis()))
                    }
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("ReadAlong", "Readable worker failed: ${failure.javaClass.simpleName}")
            if (PreparationRetry.record(app.database, bookId, id.toString(), failure)) Result.retry()
            else {
                fail(app, bookId, "Automatic retries could not finish this section. Check model, audio access and storage, then retry. Completed text and saved positions are preserved.")
                Result.failure()
            }
        }
        if (app.database.processingDao().find(bookId)?.state in listOf("READY", "FAILED")) {
            try { app.processing.advanceQueue() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.util.Log.w("ReadAlong", "The next queued book will retry when scheduled.") }
        }
        return result
    }
    private suspend fun restoreForegroundOwner(app: ReadAlongApplication, bookId: String): Result {
        try {
            val handedOff = try { app.processing.handoffToForeground(bookId, id.toString()) }
            catch (failure: Exception) {
                if (android.os.Build.VERSION.SDK_INT < 31 || failure !is android.app.ForegroundServiceStartNotAllowedException) throw failure
                // WorkManager owns this brief foreground bridge when a background broadcast
                // cannot start the dedicated service directly. No inference uses job quotas.
                val notification = com.example.readalong.data.transcription.TranscriptionNotifications.recovery(app, bookId)
                val type = if (android.os.Build.VERSION.SDK_INT >= 35) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                    else android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                setForeground(androidx.work.ForegroundInfo(com.example.readalong.data.transcription.TranscriptionNotifications.recoveryId(bookId), notification, type))
                app.processing.handoffToForeground(bookId, id.toString())
            }
            if (!handedOff) return Result.success()
            // Keep the bridge until startForeground has acknowledged the handoff. A service
            // that completes or is explicitly paused during this window needs no restart.
            withTimeout(15_000) {
                while (!AudiobookTranscriptionService.running(bookId, id.toString())) {
                    val latest = app.database.processingDao().find(bookId)
                    if (latest == null || latest.workId != id.toString() || !ProcessingState.valueOf(latest.state).active) break
                    delay(50)
                }
            }
            return Result.success()
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            return Result.retry()
        } catch (failure: Exception) {
            android.util.Log.w("ReadAlong", "Foreground recovery deferred: ${failure.javaClass.simpleName}")
            return Result.retry()
        }
    }

    private suspend fun fail(app: ReadAlongApplication, bookId: String, message: String) = app.database.withTransaction {
        val job = app.database.processingDao().find(bookId)
        if (job?.workId == id.toString() && ProcessingState.valueOf(job.state).active)
            app.database.processingDao().put(job.copy(state = "FAILED", message = message, updatedAt = System.currentTimeMillis()))
    }

}
