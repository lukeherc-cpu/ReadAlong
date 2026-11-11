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

/** Downloads and unlocks one Audible book, saves it in a library folder and queues its ReadAlong. */
class AudibleDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = AudibleStore.get(applicationContext)
        val asin = inputData.getString(ASIN) ?: return@withContext Result.failure()
        val book = store.book(asin) ?: return@withContext Result.failure()
        val account = store.account.value ?: return@withContext Result.failure().also { store.report(asin, AudibleDownload.Failed("Sign in to Audible again.")) }
        try {
            // Android can refuse a foreground start while ReadAlong is in the background (a long Download all
            // queue): the download still runs, it just shows no notification until the next book.
            runCatching { setForeground(foreground(book.title, null)) }
            var shown = -1
            val app = applicationContext as ReadAlongApplication
            val folder = AudibleLibraryFolder(applicationContext, app.audioImporter)
            val name = AudibleDownloader.safeName(book.title)
            // Books downloaded by a ReadAlong before 0.49.0 move into the folder first, keeping their progress.
            runCatching { folder.adoptOldDownloads(store) }
            val ids = if (folder.alreadyPlaced(name).isNotEmpty()) folder.addPlaced(name) else {
                val files = AudibleDownloader.download(AudibleClient(account, store::save), book, store.folder(asin)) { stage, fraction ->
                    if (isStopped) throw CancellationException()
                    store.report(asin, AudibleDownload.Running(stage, fraction))
                    val percent = (fraction * 100).toInt()
                    if (percent != shown) { shown = percent; showProgress(book.title, fraction) }
                }
                // Into the library folder (or ReadAlong's own "Audible Audiobooks" folder) as a book folder of its own.
                folder.place(name, files)
            }
            // Into the transcription queue behind whatever is already there, like choosing "Later" for a new book.
            ids.filter { app.database.processingDao().find(it) == null }.forEach { app.processing.chooseImportPriority(it, first = false) }
            store.finished(asin, ids)
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
