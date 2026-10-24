package com.example.readalong.data.processing

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.room.withTransaction
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.transcription.*
import com.example.readalong.domain.transcription.ProcessingState
import kotlinx.coroutines.*

/** One foreground service owns one checkpointed book at a time. */
class AudiobookTranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Session(val workId: String, val task: Job)
    // Mutated only on Main; recognition and checkpoint work runs on IO.
    private val sessions = linkedMapOf<String, Session>()
    private val wakeLock by lazy { getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ReadAlong:transcription").apply { setReferenceCounted(false) } }
    private var foregroundBook: String? = null
    private var latestStartId = 0
    private var pendingCommands = 0
    private var destroyed = false
    private val app get() = application as ReadAlongApplication
    override fun onCreate() { super.onCreate(); activeService = java.lang.ref.WeakReference(this) }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun foreground(book: String, title: String, progress: String) {
        val notification = TranscriptionNotifications.build(this, title, progress, book, 0)
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(TranscriptionNotifications.notificationId(book), notification,
            if (android.os.Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(TranscriptionNotifications.notificationId(book), notification)
        foregroundBook = book
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val book = intent?.getStringExtra("bookId") ?: return START_NOT_STICKY.also { if (sessions.isEmpty()) stopSelf(startId) }
        if (intent.action == ACTION_RESUME) {
            // A notification PendingIntent starts this service directly. Acknowledge Android's
            // foreground deadline BEFORE Room, WorkManager or audio-plan work can suspend.
            try { foreground(book, intent.getStringExtra("title") ?: "Audiobook", "Resuming transcription…") }
            catch (failure: Exception) {
                TranscriptionNotifications.stopped(this, book, "Transcription paused", "Android could not start background transcription. Open ReadAlong to resume; completed text is saved.")
                if (sessions.isEmpty()) stopSelf(startId)
                return START_NOT_STICKY
            }
            pendingCommands++
            scope.launch {
                try {
                    val job = app.database.processingDao().find(book)
                    if (job != null && (job.state in setOf("CANCELLED", "FAILED", "WAITING_QUEUE") || ProcessingState.valueOf(job.state).active))
                        app.processing.transcribeNext(book)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    TranscriptionNotifications.stopped(this@AudiobookTranscriptionService, book, "Transcription paused", "Couldn't resume transcription. Completed text is saved; tap Retry.")
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) {
                        pendingCommands--
                        // stopSelfResult rejects this old startId when prepare() has delivered
                        // (or queued) the actual work command. Never stop that newer session.
                        if (sessions.isEmpty() && pendingCommands == 0 && stopSelfResult(startId)) {
                            ServiceCompat.stopForeground(this@AudiobookTranscriptionService, ServiceCompat.STOP_FOREGROUND_DETACH)
                            foregroundBook = null
                        }
                    }
                }
            }
            return START_NOT_STICKY
        }
        val workId = intent.getStringExtra("workId") ?: return START_NOT_STICKY.also { if (sessions.isEmpty()) stopSelf(startId) }
        // Every foreground start must be acknowledged, including duplicate/stale commands.
        // An old session can finish while a newer start is waiting for Main delivery.
        try { foreground(book, intent.getStringExtra("title") ?: "Preparing audiobook", intent.getStringExtra("progress") ?: "Chapter · 0% / Full book 0%") }
        catch (failure: Exception) {
            scope.launch {
                // This scope has no exception handler; an uncaught database error would crash the app.
                try { app.processing.recoverInterrupted(book, workId, "Android deferred background transcription (${failure.javaClass.simpleName}).") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (recovery: Exception) { android.util.Log.w("ReadAlong", "Deferred-start recovery failed: ${recovery.javaClass.simpleName}") }
                withContext(Dispatchers.Main) { if (sessions.isEmpty()) stopSelf(startId) }
            }
            return START_NOT_STICKY
        }
        if (running(book, workId) && sessions[book]?.task?.isActive == true) return START_REDELIVER_INTENT
        if (sessions.filterKeys { it != book }.values.count { it.task.isActive } >= ProcessingRepository.MAX_ACTIVE) {
            scope.launch {
                try { status(book, workId, "QUEUED", "Waiting for the previous transcription session to close.") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { android.util.Log.w("ReadAlong", "Queue status update failed: ${failure.javaClass.simpleName}") }
            }
            return START_NOT_STICKY
        }
        if (!wakeLock.isHeld) wakeLock.acquire(6 * 60 * 60_000L)
        val previous = sessions[book]?.task
        previous?.cancel()
        runningBooks[book] = workId
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try { previous?.join(); prepareBook(book, workId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status(book, workId, "FAILED", "Transcription stopped. ${PreparationRetry.explanation(failure)} Completed text is saved.") }
            finally { withContext(NonCancellable + Dispatchers.Main) { finishBook(book, workId) } }
        }
        sessions[book] = Session(workId, task)
        task.start()
        return START_REDELIVER_INTENT
    }
    private suspend fun prepareBook(book: String, workId: String) {
        val resources = TranscriptionResources(app)
        var complete = false
        RecognitionConnection(app).withSession { connection ->
        while (!complete && currentCoroutineContext().isActive) {
            val job = app.database.processingDao().find(book) ?: break
            if (job.workId != workId || !ProcessingState.valueOf(job.state).active || !app.processing.isTurn(book, workId)) break
            if (ChargingPolicy.shouldWait(app)) { app.processing.waitForCharging(book, workId); break }
            if (!wakeLock.isHeld) wakeLock.acquire(6 * 60 * 60_000L)
            if (resources.tooHot()) {
                status(book, workId, "QUEUED", "Device is hot. Waiting to cool; completed text is saved.")
                delay(30_000); continue
            }
            try {
                complete = withTimeout(9 * 60_000L) {
                    run {
                        val began = android.os.SystemClock.elapsedRealtime()
                        var previousTime = began
                        val budget = com.example.readalong.domain.transcription.TranscriptionBatch()
                        val engine = WhistleTranscriptionEngine(app, app.speechModel, job.engineId, connection) { message -> status(book, workId, "PROCESSING", message) }
                        ProcessingRunner(app.database, app.generatedEpub).run(book, workId, engine, com.example.readalong.domain.transcription.TranscriptionBatch.MAX_UNITS) {
                            try { app.importedEpub.requestAlignment(book) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                            val now = android.os.SystemClock.elapsedRealtime()
                            val keepGoing = budget.continueAfter(now - previousTime, now - began, resources.conserve())
                            previousTime = now
                            keepGoing && !ChargingPolicy.shouldWait(app) && app.processing.isTurn(book, workId)
                        }
                    }
                }
                app.importedEpub.requestAlignment(book)
                if (!complete) {
                    status(book, workId, "QUEUED", "Completed text saved. Continuing transcription…")
                    if (resources.conserve()) delay(if (resources.warm()) 30_000 else 1_000)
                    else yield()
                }
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                if (PreparationRetry.record(app.database, book, workId, failure)) delay(30_000)
                else { status(book, workId, "FAILED", "Could not finish this section. ${PreparationRetry.explanation(failure)} Completed text is saved."); break }
            }
        }
        }
    }
    private suspend fun finishBook(book: String, workId: String) {
        if (destroyed || sessions[book]?.workId != workId) return
        sessions.remove(book); runningBooks.remove(book, workId)
        val saved = app.database.processingDao().find(book)
        // Keep the foreground lifetime across books. Starting the successor after stopSelf
        // loses Android's background-start eligibility and falls back to scheduled work.
        if (saved?.state in setOf("READY", "FAILED")) {
            try { withContext(Dispatchers.IO) { app.processing.advanceQueue() } }
            catch (failure: Exception) { app.advanceTranscriptionQueue() }
        }
        val other = sessions.entries.firstOrNull { it.value.task.isActive && !TranscriptionNotifications.dismissed(app, it.key) }
        if (other != null) {
            val title = app.database.bookDao().findBook(other.key)?.title ?: "Preparing audiobook"
            val progress = app.database.processingDao().find(other.key)?.notificationLabel(app.database).orEmpty()
            runCatching { foreground(other.key, title, progress) }
        } else if (sessions.isEmpty() && pendingCommands == 0) {
            // An explicit pause keeps its Play notification. stopSelfResult removes an
            // attached foreground card immediately, so detach this paused card first.
            // Completed-book handoffs retain foreground protection until the stop is accepted.
            if (saved?.state == "CANCELLED" && foregroundBook == book) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                foregroundBook = null
            }
            if (!stopSelfResult(latestStartId)) return
            // Check with ActivityManager BEFORE detaching: a newer undelivered start must
            // keep this service alive and foreground until onStartCommand acknowledges it.
            val owner = foregroundBook
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            foregroundBook = null
            if (owner != null && app.database.processingDao().find(owner)?.state != "CANCELLED")
                getSystemService(android.app.NotificationManager::class.java).cancel(TranscriptionNotifications.notificationId(owner))
            if (wakeLock.isHeld) wakeLock.release()
        }
        // Detach or switch before removing a completed foreground card; Android protects attached cards.
        if (foregroundBook != book && saved?.state !in setOf("CANCELLED", "QUEUED", "PROCESSING", "PARTIAL_READY"))
            getSystemService(android.app.NotificationManager::class.java).cancel(TranscriptionNotifications.notificationId(book))
    }

    private suspend fun status(book: String, workId: String, state: String, message: String) = app.database.withTransaction {
        val current = app.database.processingDao().find(book) ?: return@withTransaction
        if (current.workId == workId && (ProcessingState.valueOf(current.state).active || state == "FAILED" && current.state == "FAILED"))
            app.database.processingDao().put(current.copy(state = state, message = message, updatedAt = System.currentTimeMillis()))
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        runningBooks.toMap().forEach { (book, work) -> app.transcriptionInterrupted(book, work, "Android’s background time limit was reached. Resume transcription; completed text is saved.") }
        sessions.values.forEach { it.task.cancel() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH); stopSelf()
    }
    override fun onDestroy() {
        destroyed = true
        val interrupted = runningBooks.toMap()
        if (wakeLock.isHeld) wakeLock.release()
        scope.cancel()
        interrupted.forEach { (book, work) -> app.transcriptionInterrupted(book, work) }
        runningBooks.clear()
        if (activeService?.get() === this) activeService = null
        super.onDestroy()
    }
    companion object {
        const val ACTION_RESUME = "com.example.readalong.RESUME_TRANSCRIPTION"
        private var activeService: java.lang.ref.WeakReference<AudiobookTranscriptionService>? = null
        private val runningBooks = java.util.concurrent.ConcurrentHashMap<String, String>()
        val activeBook: String? get() = runningBooks.keys.firstOrNull()
        val activeWorkId: String? get() = activeBook?.let(runningBooks::get)
        fun running(book: String, workId: String) = runningBooks[book] == workId
        /** Cancel this book without disturbing a newer service command. */
        fun pauseBook(book: String) { activeService?.get()?.sessions?.get(book)?.task?.cancel() }
    }
}
