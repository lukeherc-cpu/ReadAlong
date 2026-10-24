package com.example.readalong.data.transcription

import androidx.compose.ui.graphics.toArgb
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.readalong.MainActivity
import com.example.readalong.R
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.processing.progress
import com.example.readalong.data.processing.notificationLabel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/** One ongoing notification, including between checkpoint workers. No extra foreground service. */
object TranscriptionNotifications {
    const val ID = 4301
    fun notificationId(bookId: String): Int = 10_000 + (bookId.hashCode() and 0x3fffffff)
    internal fun recoveryId(bookId: String): Int = notificationId(bookId) xor 0x40000000
    internal fun recovery(context: Context, bookId: String): android.app.Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Audiobook transcription", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_book)
            .setContentTitle("Resuming transcription").setContentText("Restoring saved progress…")
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setGroup("readalong-transcription").setSortKey(sortKey(positions[bookId] ?: 0L, bookId)).build()
    }
    /** Notification interaction grants the service start directly, before any async work. */
    internal fun resumeAction(context: Context, bookId: String, title: String): PendingIntent =
        PendingIntent.getForegroundService(context, notificationId(bookId),
            Intent(context, com.example.readalong.data.processing.AudiobookTranscriptionService::class.java)
                .setAction(com.example.readalong.data.processing.AudiobookTranscriptionService.ACTION_RESUME)
                .setData(android.net.Uri.parse("readalong://resume/${android.net.Uri.encode(bookId)}"))
                .putExtra("bookId", bookId).putExtra("title", title),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun limitReached(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(STOPPED_CHANNEL, "Transcription updates", NotificationManager.IMPORTANCE_DEFAULT))
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) manager.notify(ID + 4,
            NotificationCompat.Builder(context, STOPPED_CHANNEL).setSmallIcon(R.drawable.ic_book)
                .setContentTitle("One book transcribes at a time")
                .setContentText("Pause a transcription before starting another book.").setAutoCancel(true).build())
    }
    private const val STOPPED_CHANNEL = "transcription-stopped"
    private const val CHANNEL = "whisper-transcription"
    private val deliveryLock = Any()
    private val positions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun queuePosition(bookId: String, position: Long) { positions[bookId] = position }
    internal fun sortKey(position: Long, bookId: String): String = java.lang.Long.toUnsignedString(position xor Long.MIN_VALUE).padStart(20, '0') + ":" + bookId
    private fun dismissals(context: Context) = context.getSharedPreferences("transcription_notification_visibility", Context.MODE_PRIVATE)
    fun dismissed(context: Context, bookId: String): Boolean = dismissals(context).getBoolean(bookId, false)
    fun dismiss(context: Context, bookId: String) = synchronized(deliveryLock) {
        dismissals(context).edit().putBoolean(bookId, true).apply()
    }
    fun showForExplicitStart(context: Context, bookId: String) = synchronized(deliveryLock) {
        dismissals(context).edit().remove(bookId).remove("when:$bookId").apply()
    }
    private fun stableWhen(context: Context, bookId: String): Long = synchronized(deliveryLock) {
        val prefs = dismissals(context)
        val key = "when:$bookId"
        prefs.getLong(key, 0L).takeIf { it > 0L } ?: System.currentTimeMillis().also { prefs.edit().putLong(key, it).apply() }
    }
    fun build(context: Context, title: String, label: String, bookId: String, percent: Int): android.app.Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Audiobook transcription", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, notificationId(bookId), com.example.readalong.ui.launch.LaunchAppearance.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getBroadcast(context, notificationId(bookId), Intent(context, PauseTranscriptionReceiver::class.java).putExtra("bookId", bookId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return compact(context, title, label, "Creating readable version", percent, open, pause, bookId = bookId)
    }
    private fun compact(context: Context, title: String, label: String, estimate: String, percent: Int, open: PendingIntent, pause: PendingIntent, paused: Boolean = false, bookId: String): android.app.Notification {
        val settings = (context.applicationContext as? ReadAlongApplication)?.appearanceSettings ?: com.example.readalong.data.preferences.AppPreferences()
        val palette = com.example.readalong.ui.theme.basicPalette(settings.basicColor)
        val accent = palette.accent.toArgb()
        val progressImage = android.graphics.Bitmap.createBitmap(256, 5, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(progressImage).apply {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = accent; paint.alpha = 55; drawRoundRect(0f, 0f, 256f, 5f, 2.5f, 2.5f, paint)
            paint.alpha = 255; drawRoundRect(0f, 0f, 256f * percent.coerceIn(0, 100) / 100, 5f, 2.5f, 2.5f, paint)
        }
        val pauseImage = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(pauseImage).apply {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = accent; drawCircle(48f, 48f, 47f, paint)
            paint.color = palette.background.toArgb()
            if (paused) {
                val triangle = android.graphics.Path().apply { moveTo(36f, 27f); lineTo(70f, 48f); lineTo(36f, 69f); close() }
                drawPath(triangle, paint)
            } else { drawRoundRect(32f, 31f, 42f, 65f, 3f, 3f, paint); drawRoundRect(54f, 31f, 64f, 65f, 3f, 3f, paint) }
        }
        val content = android.widget.RemoteViews(context.packageName, R.layout.transcription_notification).apply {
            setTextViewText(R.id.transcription_title, title)
            setTextViewText(R.id.transcription_label, label.replace(" / ", "  |  "))
            setImageViewBitmap(R.id.transcription_progress, progressImage)
            setImageViewBitmap(R.id.transcription_pause, pauseImage)
            setOnClickPendingIntent(R.id.transcription_pause, pause)
            setContentDescription(R.id.transcription_pause, if (paused) "Resume transcription" else "Pause transcription")
        }
        val expanded = android.widget.RemoteViews(context.packageName, R.layout.transcription_notification_expanded).apply {
            setTextViewText(R.id.transcription_title, title)
            val chapterLabel = label.substringBeforeLast(" / Full book ")
            val percentLabel = Regex("(\\d+)%$").find(chapterLabel)?.groupValues?.get(1) ?: "0"
            setTextViewText(R.id.transcription_chapter, chapterLabel.substringBeforeLast(" · "))
            setTextViewText(R.id.transcription_chapter_progress, "Chapter · $percentLabel% complete")
            setTextViewText(R.id.transcription_book, "Full book · $percent% complete")
            setImageViewBitmap(R.id.transcription_progress, progressImage)
            setImageViewBitmap(R.id.transcription_pause, pauseImage)
            setOnClickPendingIntent(R.id.transcription_pause, pause)
            setContentDescription(R.id.transcription_pause, if (paused) "Resume transcription" else "Pause transcription")
        }
        val delete = PendingIntent.getBroadcast(context, notificationId(bookId), Intent(context, DismissTranscriptionReceiver::class.java)
            .setData(android.net.Uri.parse("readalong://dismiss/${android.net.Uri.encode(bookId)}"))
            .putExtra("bookId", bookId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_book).setContentTitle(title).setContentText(label)
            .setSubText(estimate).setCustomContentView(content).setCustomBigContentView(expanded)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle()).setContentIntent(open)
            .addExtras(android.os.Bundle().apply { putString("readalongBookId", bookId) }).setGroup("readalong-transcription").setSortKey(sortKey(positions[bookId] ?: 0L, bookId)).setWhen(stableWhen(context, bookId)).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY).setDeleteIntent(delete).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build()
    }

    fun stopped(context: Context, bookId: String, title: String, explanation: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(STOPPED_CHANNEL, "Transcription needs attention", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, bookId.hashCode(), com.example.readalong.ui.launch.LaunchAppearance.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val retry = resumeAction(context, bookId, title)
        val text = when {
            explanation.contains("audio file order", true) -> "Audio file changed."
            explanation.length > 140 -> "Transcription paused unexpectedly. Completed text is saved."
            else -> explanation
        }
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) manager.notify("stopped:$bookId", ID + 1,
            NotificationCompat.Builder(context, STOPPED_CHANNEL).setSmallIcon(R.drawable.ic_book).setContentTitle("Transcription stopped").setSubText(title)
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open)
                .setAutoCancel(true).addAction(R.drawable.ic_forward, "Retry", retry).build())
    }

    suspend fun observe(app: ReadAlongApplication) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Audiobook transcription", NotificationManager.IMPORTANCE_LOW))
        // Restart only when the set of cards can change. Status text and checkpoint counts change
        // many times per chunk; the 2-second loop below already reads them fresh. Restarting on
        // each one cancelled updates before they were posted, especially for very long books.
        app.processing.queue.map { items -> items.map { it.copy(message = null, completedUnits = 0, totalUnits = 0) } }
            .distinctUntilChanged().collectLatest { queue ->
            queue.forEach { positions[it.bookId] = it.queuePosition }
            for (item in queue) {
                if (item.state == "FAILED") {
                    val job = app.database.processingDao().find(item.bookId) ?: continue
                    val key = "stop_notification:${item.bookId}"
                    if (app.database.bookDao().metadata(key) != job.workId && NotificationManagerCompat.from(app).areNotificationsEnabled()) {
                        stopped(app, item.bookId, item.title, job.message ?: "Transcription stopped. Completed text is saved.")
                        app.database.bookDao().putMetadata(com.example.readalong.data.local.LibraryMetadataEntity(key, job.workId))
                    }
                } else if (item.state in listOf("QUEUED", "PROCESSING", "PARTIAL_READY", "READY")) manager.cancel("stopped:${item.bookId}", ID + 1)
            }
            val pausedBook = app.database.bookDao().metadata("paused_transcription_notification")
            val visible = queue.filter { item ->
                item.state in setOf("QUEUED", "PROCESSING", "PARTIAL_READY", "GENERATING_EPUB") ||
                    item.state == "CANCELLED" && (item.bookId == pausedBook || app.database.bookDao().metadata("paused_notification:${item.bookId}") == "true")
            }
            val ids = visible.map { notificationId(it.bookId) }.toSet()
            manager.activeNotifications.filter { it.notification.extras.containsKey("readalongBookId") && it.id !in ids }.forEach { manager.cancel(it.id) }
            // Remove the previous version's single progress card after upgrading.
            manager.cancel(ID)
            while (currentCoroutineContext().isActive && visible.isNotEmpty()) {
                for (item in visible) try {
                    val job = app.database.processingDao().find(item.bookId) ?: continue
                    val paused = job.state == "CANCELLED"
                    val progress = job.progress()
                    val estimate = com.example.readalong.data.processing.TranscriptionEstimate.label(app.database, job)
                    val chapter = job.notificationLabel(app.database)
                    val id = notificationId(job.bookId)
                    val open = PendingIntent.getActivity(app, id, com.example.readalong.ui.launch.LaunchAppearance.intent(app), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    val action = if (paused) resumeAction(app, job.bookId, item.title) else PendingIntent.getBroadcast(app, id, Intent(app, PauseTranscriptionReceiver::class.java)
                        .setData(android.net.Uri.parse("readalong://action/${android.net.Uri.encode(job.bookId)}"))
                        .putExtra("bookId", job.bookId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    if (NotificationManagerCompat.from(app).areNotificationsEnabled()) {
                        val status = if (paused) "Paused · tap play to resume" else if (job.message?.startsWith("Waiting for charging") == true) "Waiting for charging" else estimate
                        val notification = compact(app, item.title, chapter, status, progress.bookPercent, open, action, paused, job.bookId)
                        // A swiped card stays hidden, but Android re-shows the foreground service's own
                        // "0%" card whenever the service restarts. Keep a card that is on screen current.
                        synchronized(deliveryLock) {
                            if (!dismissed(app, job.bookId) || manager.activeNotifications.any { it.id == id }) manager.notify(id, notification)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    // Skip this refresh for one book; keep updating the others and retry in 2 seconds.
                    android.util.Log.w("ReadAlong", "Progress notification refresh skipped: ${failure.javaClass.simpleName}")
                }
                delay(2_000)
            }
        }
    }
}

class PauseTranscriptionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bookId = intent.getStringExtra("bookId") ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { (context.applicationContext as ReadAlongApplication).processing.cancel(bookId) }
            catch (failure: Exception) {
                // No exception handler covers this scope: an uncaught error here would crash the app.
                android.util.Log.w("ReadAlong", "Pause from notification failed: ${failure.javaClass.simpleName}")
            }
            finally { pending.finish() }
        }
    }
}

/** Compatibility for notifications posted by an older installed version. */
class ResumeTranscriptionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bookId = intent.getStringExtra("bookId") ?: return
        try {
            androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, com.example.readalong.data.processing.AudiobookTranscriptionService::class.java)
                    .setAction(com.example.readalong.data.processing.AudiobookTranscriptionService.ACTION_RESUME)
                    .putExtra("bookId", bookId))
        } catch (_: Exception) {
            TranscriptionNotifications.stopped(context, bookId, "Transcription paused", "Android could not resume transcription. Tap Retry; completed text is saved.")
        }
    }
}

/** A swipe hides this run; progress updates and process recreation must not restore it. */
class DismissTranscriptionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("bookId")?.let { TranscriptionNotifications.dismiss(context, it) }
    }
}
