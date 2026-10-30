package com.example.readalong.data.audible

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.readalong.ReadAlongApplication
import com.example.readalong.audible.AudibleClient
import com.example.readalong.audible.AudibleDownloader
import com.example.readalong.audible.AudibleThrottledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Downloads and unlocks one Audible book, adds it to the library and queues its ReadAlong. */
class AudibleDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = AudibleStore.get(applicationContext)
        val asin = inputData.getString(ASIN) ?: return@withContext Result.failure()
        val book = store.book(asin) ?: return@withContext Result.failure()
        val account = store.account.value ?: return@withContext Result.failure().also { store.report(asin, AudibleDownload.Failed("Sign in to Audible again.")) }
        try {
            setForeground(foreground(book.title, null))
            var shown = -1
            val files = AudibleDownloader.download(AudibleClient(account, store::save), book, store.folder(asin)) { stage, fraction ->
                if (isStopped) throw CancellationException()
                store.report(asin, AudibleDownload.Running(stage, fraction))
                val percent = (fraction * 100).toInt()
                if (percent != shown) { shown = percent; showProgress(book.title, fraction) }
            }
            val app = applicationContext as ReadAlongApplication
            val imported = app.audioImporter.importFiles(store.uris(files), persistAccess = false)
            // Into the transcription queue behind whatever is already there, like choosing "Later" for a new book.
            imported.bookIds.forEach { app.processing.chooseImportPriority(it, first = false) }
            store.finished(asin)
            Result.success()
        } catch (cancelled: CancellationException) {
            store.report(asin, AudibleDownload.Failed("Download stopped. Tap Download to continue."))
            throw cancelled
        } catch (throttled: AudibleThrottledException) {
            // Further licence requests now would only extend the limit: stop the rest of the queue.
            store.report(asin, AudibleDownload.Failed(throttled.message.orEmpty()))
            store.cancel()
            Result.success()
        } catch (failure: Exception) {
            // Reported on the book; success keeps the books queued after it going.
            store.report(asin, AudibleDownload.Failed(failure.message ?: "The download didn't finish."))
            Result.success()
        }
    }

    private fun showProgress(title: String, fraction: Float) =
        applicationContext.getSystemService(NotificationManager::class.java).notify(id.hashCode(), notification(title, fraction))

    private fun notification(title: String, fraction: Float?): android.app.Notification {
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Audible downloads", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(com.example.readalong.R.drawable.ic_notification)
            .setContentTitle("Downloading from Audible").setContentText(title).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
    }

    private fun foreground(title: String, fraction: Float?) = if (Build.VERSION.SDK_INT >= 29)
        ForegroundInfo(id.hashCode(), notification(title, fraction), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id.hashCode(), notification(title, fraction))

    companion object {
        const val ASIN = "asin"
        private const val CHANNEL = "audible-downloads"
    }
}
