package com.example.readalong.data.link

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.readalong.ReadAlongApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

/**
 * Syncs linked folders with the computer when the phone is on Wi-Fi: on launch, every few hours, and from
 * "Sync now". Long copies run as a foreground job with a progress notification.
 */
class LinkSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = (applicationContext as ReadAlongApplication).folderLink
        if (sync.store.value.computer == null || sync.store.value.pairs.isEmpty()) return Result.success()
        return coroutineScope {
            val updates = launch {
                sync.progress.collectLatest { progress ->
                    if (progress != null) { runCatching { setForeground(foreground(progress)) }; kotlinx.coroutines.delay(1_000) }
                }
            }
            try {
                sync.sync(); Result.success()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                sync.store.update { it.copy(lastResult = failure.message ?: "Sync didn't finish.") }
                // Settings shows why; the next run tries again.
                Result.success()
            } finally { updates.cancel() }
        }
    }

    private fun foreground(progress: SyncProgress): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sync with computer", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(com.example.readalong.R.drawable.ic_notification)
            .setContentTitle("Syncing with your computer").setContentText(progress.text).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .apply { progress.fraction?.let { setProgress(1000, (it * 1000).toInt(), false) } ?: setProgress(0, 0, true) }
            .addAction(0, "Stop", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIFICATION, notification)
    }

    companion object {
        private const val CHANNEL = "computer-sync"
        private const val NOTIFICATION = 7410
        private const val NOW = "link-sync"
        private const val PERIODIC = "link-sync-periodic"
        private val wifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        /** Syncs as soon as the phone is on Wi-Fi. */
        fun syncNow(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LinkSyncWorker>().setConstraints(wifi).build())

        fun schedule(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LinkSyncWorker>(3, TimeUnit.HOURS).setConstraints(wifi).build())

        fun cancel(context: Context) { WorkManager.getInstance(context).apply { cancelUniqueWork(NOW); cancelUniqueWork(PERIODIC) } }

        fun running(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOW)
    }
}
