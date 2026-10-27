package com.example.readalong.data.processing

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.example.readalong.data.local.*
import com.example.readalong.domain.transcription.ProcessingState
import com.example.readalong.data.transcription.SpeechModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit

class ProcessingRepository(private val context: Context, private val database: ReadAlongDatabase, private val model: SpeechModelStore, private val launchPreparation: (android.content.Intent) -> Unit = { androidx.core.content.ContextCompat.startForegroundService(context, it) }) {
    private val manager = WorkManager.getInstance(context)
    private val lock = Mutex()
    private val queueCommands = Mutex()
    val queue = database.processingDao().observeQueue()
    fun observe(bookId: String) = database.processingDao().observe(bookId)
    fun execution(bookId: String) = preparationExecution(context, database, bookId)
    private fun name(bookId: String) = "readable:$bookId"
    private fun recoveryName(bookId: String) = "readable-recovery:$bookId"
    internal suspend fun guardForeground(bookId: String, workId: String, append: Boolean = false) {
        val guard = OneTimeWorkRequestBuilder<PreparationRecoveryWorker>()
            .setInputData(workDataOf("bookId" to bookId, "workId" to workId))
            .setInitialDelay(30, TimeUnit.SECONDS).addTag("readable-recovery").build()
        manager.enqueueUniqueWork(recoveryName(bookId), if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, guard).result.get()
    }
    suspend fun recoverInterrupted(bookId: String, expectedWorkId: String, reason: String = "Your ReadAlong was interrupted.") = lock.withLock { withContext(Dispatchers.IO) {
        val current = database.processingDao().find(bookId) ?: return@withContext
        if (current.workId != expectedWorkId || !ProcessingState.valueOf(current.state).active || AudiobookTranscriptionService.running(bookId, expectedWorkId)) return@withContext
        val next = request(bookId)
        database.processingDao().put(current.copy(workId = next.id.toString(), state = "QUEUED",
            message = if (ChargingPolicy.shouldWait(context)) "Waiting for charging. Progress is saved; it continues when you plug in."
                else "$reason Resuming automatically from saved progress…", updatedAt = System.currentTimeMillis()))
        manager.enqueueUniqueWork(name(bookId), ExistingWorkPolicy.REPLACE, next).result.get()
    } }

    internal suspend fun request(bookId: String, userInitiated: Boolean = false, cooldownMs: Long = 0, expedite: Boolean = false): OneTimeWorkRequest {
        val chargingOnly = ChargingPolicy.restricted(com.example.readalong.data.preferences.AppPreferencesStore(context).preferences.first())
        val expedited = (userInitiated && !chargingOnly || expedite) && android.os.Build.VERSION.SDK_INT >= 31
        val unrestricted = context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        return OneTimeWorkRequestBuilder<ReadableVersionWorker>()
            .setInputData(workDataOf("bookId" to bookId))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).setRequiresCharging(chargingOnly && !ChargingPolicy.connectedToPower(context)).setRequiresBatteryNotLow(!chargingOnly && !expedited && !unrestricted).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("readable-version")
            .setInitialDelay(cooldownMs, TimeUnit.MILLISECONDS)
            .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
    }

    /** Recovery jobs only start/restore the foreground owner; they never perform inference. */
    internal suspend fun handoffToForeground(bookId: String, expectedWorkId: String): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val job = database.processingDao().find(bookId) ?: return@withContext false
            if (job.workId != expectedWorkId || !ProcessingState.valueOf(job.state).active || !isTurn(bookId, expectedWorkId)) return@withContext false
            if (AudiobookTranscriptionService.running(bookId, expectedWorkId)) return@withContext true
            if (ChargingPolicy.shouldWait(context)) return@withContext false
            val book = database.bookDao().findBook(bookId) ?: return@withContext false
            guardForeground(bookId, expectedWorkId)
            launchPreparation(android.content.Intent(context, AudiobookTranscriptionService::class.java)
                .putExtra("bookId", bookId).putExtra("workId", expectedWorkId).putExtra("title", book.title)
                .putExtra("progress", job.notificationLabel(database)))
            true
        }
    }

    suspend fun imported(bookId: String) = lock.withLock {
        // Importing a collection must not pick a book for the listener or enqueue the whole library.
        if (database.processingDao().find(bookId) == null) database.processingDao().put(
            ProcessingJobEntity(bookId, UUID.randomUUID().toString(), SpeechModelStore.ID, "WAITING_SELECTION", 0, 0,
                "Ready to play. Make a ReadAlong from the player whenever you like.", System.currentTimeMillis()))
    }
    /** Opening the listening book promotes eligible work; explicit pauses and finished books remain intact. */
    suspend fun ensureStarted(bookId: String) = prepare(bookId, onlyIfNeeded = true,
        first = database.bookDao().metadata("keep_queue_order:$bookId") != "true")
    suspend fun chooseImportPriority(bookId: String, first: Boolean) {
        database.bookDao().putMetadata(LibraryMetadataEntity("keep_queue_order:$bookId", (!first).toString()))
        prepare(bookId, first = first)
    }
    suspend fun transcribeNext(bookId: String) = queueCommands.withLock {
        if (database.processingDao().find(bookId)?.state == "READY") return@withLock
        database.bookDao().removeMetadata("keep_queue_order:$bookId")
        prepare(bookId, first = true)
    }
    /** Play promotes this book; the displaced book waits with its checkpoint intact. */
    suspend fun playQueued(bookId: String) = transcribeNext(bookId)
    suspend fun addAllToQueue() = queueCommands.withLock {
        queue.first().filter { it.state == "WAITING_SELECTION" }.forEach { addToQueue(it.bookId) }
    }
    /** Explicit repair after an engine/timing update; opening a book never invokes this. */
    suspend fun rebuild(bookId: String) {
        cancel(bookId)
        prepare(bookId, first = true, fromBeginning = true)
    }
    suspend fun isTurn(bookId: String, workId: String): Boolean = database.withTransaction {
        val job = database.processingDao().find(bookId)
        job?.workId == workId && ProcessingState.valueOf(job.state).active && database.processingDao().orderedQueue().filter { ProcessingState.valueOf(it.state).active }.take(MAX_ACTIVE).any { it.bookId == bookId }
    }
    suspend fun move(bookId: String, direction: Int) = lock.withLock { withContext(Dispatchers.IO) {
        require(direction == -1 || direction == 1)
        database.withTransaction {
            val ordered = database.processingDao().registeredQueue().filter { it.state != "READY" }.toMutableList()
            val index = ordered.indexOfFirst { it.bookId == bookId }
            if (index >= 0 && index + direction in ordered.indices) {
                java.util.Collections.swap(ordered, index, index + direction)
                ordered.forEachIndexed { rank, job -> database.processingDao().position(job.bookId, rank.toLong()) }
            }
        }
    } }
    /** Reset a waiting worker's backoff without interrupting a running audio chunk. */
    private suspend fun wakeFirst(userInitiated: Boolean = false) {
        for (candidate in database.processingDao().orderedQueue().filter { ProcessingState.valueOf(it.state).active }.take(MAX_ACTIVE)) {
            val bookId = candidate.bookId
            val old = database.processingDao().find(bookId) ?: continue
            if (AudiobookTranscriptionService.running(bookId, old.workId)) continue
            val work = manager.getWorkInfoById(UUID.fromString(old.workId)).get()
            if (work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED) continue
            val next = request(bookId, userInitiated)
            database.withTransaction {
                val latest = database.processingDao().find(bookId)
                if (latest?.workId == old.workId && ProcessingState.valueOf(latest.state).active)
                    database.processingDao().put(latest.copy(workId = next.id.toString(), state = "QUEUED"))
            }
            if (database.processingDao().find(bookId)?.workId == next.id.toString())
                manager.enqueueUniqueWork(name(bookId), ExistingWorkPolicy.REPLACE, next).result.get()
        }
    }

    suspend fun modelInstalled() {
        database.processingDao().orderedQueue().filter { it.state == "WAITING_MODEL" }.forEach {
            try { if (model.available(it.engineId)) prepare(it.bookId, userInitiated = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.util.Log.w("ReadAlong", "A waiting book could not be queued; preparation can be retried from its player.") }
        }
    }
    suspend fun cancelRealJobs(modelId: String? = null) {
        database.processingDao().orderedQueue().filter { modelId == null || it.engineId == modelId }.forEach { cancel(it.bookId) }
    }
    suspend fun prepare(bookId: String, recoverMissing: Boolean = false, onlyIfNeeded: Boolean = false, first: Boolean = false, userInitiated: Boolean = !recoverMissing, fromBeginning: Boolean = false, promote: Boolean = true) = lock.withLock { withContext(Dispatchers.IO) {
        val book = requireNotNull(database.bookDao().findBook(bookId))
        val old = database.processingDao().find(bookId)
        if (!fromBeginning && first && old?.state == "READY" && (old.planJson == null ||
                (old.completedUnits == old.totalUnits && old.sourceFingerprint == TranscriptionPlan.fingerprint(database, bookId)))) return@withContext
        if (onlyIfNeeded) {
            if (book.isDemo || old?.state in listOf("CANCELLED", "FAILED")) return@withContext
            if (old?.state == "READY" && (old.planJson == null ||
                    (old.completedUnits == old.totalUnits && old.sourceFingerprint == TranscriptionPlan.fingerprint(database, bookId)))) return@withContext
        }
        // Persist the old slot as waiting BEFORE cancelling its worker or native process.
        // RecognitionSlots provides a second, process-wide exclusion boundary during teardown.
        if (userInitiated || first) {
            database.processingDao().unfinished().filter { it.bookId != bookId }.forEach { defer(it) }
        } else if (database.processingDao().unfinished().any { it.bookId != bookId }) return@withContext
        if (userInitiated && !onlyIfNeeded) database.bookDao().putMetadata(LibraryMetadataEntity("transcription_queue_running", "true"))
        val ordered = database.processingDao().registeredQueue()
        val queuePosition = if (first || userInitiated && promote) (ordered.minOfOrNull { it.queuePosition } ?: 0L) - 1L
            else old?.takeIf { it.state != "WAITING_SELECTION" }?.queuePosition ?: ((ordered.maxOfOrNull { it.queuePosition } ?: -1L) + 1L)
        val engineId = if (book.isDemo) "sample-v1" else old?.takeIf {
            !fromBeginning &&
            it.state != "READY" && it.state != "WAITING_SELECTION" &&
                !(userInitiated && !onlyIfNeeded && it.completedUnits == 0 && it.state in setOf("FAILED", "CANCELLED", "WAITING_MODEL")) &&
                model.find(it.engineId) != null
        }?.engineId ?: model.selectedModel().id
        val engineChanged = old != null && old.engineId != engineId
        if (!book.isDemo && !model.available(engineId)) {
            database.processingDao().put((old ?: ProcessingJobEntity(bookId, UUID.randomUUID().toString(), engineId,
                "WAITING_MODEL", 0, 0, null, 0)).copy(state = "WAITING_MODEL", engineId = engineId,
                workId = if (userInitiated) UUID.randomUUID().toString() else old?.workId ?: UUID.randomUUID().toString(),
                completedUnits = if (fromBeginning || engineChanged) 0 else old?.completedUnits ?: 0,
                planJson = if (fromBeginning || engineChanged) null else old?.planJson,
                sourceFingerprint = if (engineChanged) null else old?.sourceFingerprint,
                message = "Waiting for ${model.spec(engineId).name} setup. Audio is ready to play.", updatedAt = System.currentTimeMillis(), queuePosition = queuePosition))
            val application = context.applicationContext as? com.example.readalong.ReadAlongApplication
            if (application?.database === database) com.example.readalong.data.transcription.AutomaticModelDownload.ensure(context, model, requestedForBook = true)
            return@withContext
        }
        if (!fromBeginning && !book.isDemo && old != null && ProcessingState.valueOf(old.state).active && AudiobookTranscriptionService.running(bookId, old.workId)) {
            if (first || userInitiated) database.processingDao().position(bookId, queuePosition)
            return@withContext
        }
        val currentWork = manager.getWorkInfosForUniqueWork(name(bookId)).get().any { !it.state.isFinished }
        if (!recoverMissing && !userInitiated && currentWork && old != null && ProcessingState.valueOf(old.state).active) {
            if (first || userInitiated) database.processingDao().position(bookId, queuePosition)
            if (first || userInitiated) wakeFirst(userInitiated)
            return@withContext
        }
        if (userInitiated && !recoverMissing && !onlyIfNeeded) com.example.readalong.data.transcription.TranscriptionNotifications.showForExplicitStart(context, bookId)
        val request = request(bookId, userInitiated)
        com.example.readalong.data.transcription.TranscriptionNotifications.queuePosition(bookId, queuePosition)
        try {
            database.withTransaction {
                database.bookDao().removeMetadata("paused_notification:$bookId")
                val fingerprint = TranscriptionPlan.fingerprint(database, bookId)
                val continuable = !fromBeginning && old?.engineId == engineId && old.planJson != null &&
                    (old.state in listOf("CANCELLED", "FAILED", "WAITING_MODEL", "WAITING_SELECTION", "WAITING_QUEUE") || ProcessingState.valueOf(old.state).active)
                // Only file lengths changed (playing the book corrects them): keep the finished work on the new plan.
                val rebased = if (continuable && !book.isDemo && old!!.sourceFingerprint != null && old.sourceFingerprint != fingerprint)
                    TranscriptionPlan.rebase(database, old) else null
                val resumable = continuable && (old!!.sourceFingerprint == fingerprint || rebased != null)
                val plan = rebased?.planJson ?: if (resumable) old!!.planJson!! else TranscriptionPlan.encode(TranscriptionPlan.inputs(database, bookId))
                val units = TranscriptionPlan.decode(plan)
                if (rebased == null && !book.isDemo && old?.sourceFingerprint != null && old.sourceFingerprint != fingerprint) {
                    database.importedEpubDao().invalidateAudioMapping(bookId)
                    database.readingDao().clearTranscript(bookId)
                    database.importedEpubDao().find(bookId)?.let { edition -> database.importedEpubDao().put(edition.copy(state = "WAITING_TRANSCRIPT")) }
                    database.readingDao().findVersion(bookId)?.let { version -> database.readingDao().putVersion(version.copy(
                        transcriptRevision = version.transcriptRevision + 1, message = "Audio layout changed. Rebuilding text; the saved edition is retained.")) }
                }
                database.processingDao().put(ProcessingJobEntity(bookId, request.id.toString(), engineId, "QUEUED",
                    if (resumable) (rebased ?: old!!).completedUnits.coerceAtMost(units.size) else 0, units.size,
                    "Starting your ReadAlong…",
                    System.currentTimeMillis(), plan, fingerprint, queuePosition))
            }
            if (book.isDemo || ChargingPolicy.shouldWait(context)) {
                if (!book.isDemo) database.processingDao().find(bookId)?.let {
                    database.processingDao().put(it.copy(message = "Waiting for charging. Progress is saved; it continues when you plug in."))
                }
                manager.enqueueUniqueWork(name(bookId), ExistingWorkPolicy.REPLACE, request).result.get()
            } else {
                manager.cancelUniqueWork(name(bookId)).result.get()
                guardForeground(bookId, request.id.toString())
                launchPreparation(android.content.Intent(context, AudiobookTranscriptionService::class.java)
                    .putExtra("bookId", bookId).putExtra("workId", request.id.toString()).putExtra("title", book.title)
                    .putExtra("progress", database.processingDao().find(bookId)?.notificationLabel(database)))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val current = database.processingDao().find(bookId)
            if (current?.workId == request.id.toString() && ProcessingState.valueOf(current.state).active) {
                database.processingDao().put(current.copy(state = "QUEUED", message = "Android delayed the start. Your ReadAlong will continue from where it left off.", updatedAt = System.currentTimeMillis()))
                manager.enqueueUniqueWork(name(bookId), ExistingWorkPolicy.REPLACE, request).result.get()
            } else database.processingDao().put((current ?: ProcessingJobEntity(bookId, request.id.toString(), engineId, "FAILED", 0, 0, null, 0))
                .copy(state = "FAILED", message = failure.message ?: "Couldn't start this ReadAlong. Make sure the audio is still in its folder, then try again.", updatedAt = System.currentTimeMillis()))
        }
    } }

    /** Append before the current worker succeeds; Room records the intent first for crash recovery. */
    suspend fun continuePreparation(bookId: String, workId: String, cooldownMs: Long = 0) = lock.withLock { withContext(NonCancellable + Dispatchers.IO) {
        val current = database.processingDao().find(bookId) ?: return@withContext
        if (current.workId != workId || !ProcessingState.valueOf(current.state).active) return@withContext
        val next = request(bookId, cooldownMs = cooldownMs)
        database.processingDao().put(current.copy(workId = next.id.toString(), state = "QUEUED",
            message = if (ChargingPolicy.shouldWait(context)) "Waiting for charging. Progress is saved; it continues when you plug in."
                else if (cooldownMs > 0) "Cooling down before the next section. Completed text is saved."
                else "${current.completedUnits} of ${current.totalUnits} chunks ready. Waiting for Android to schedule the next chunk.", updatedAt = System.currentTimeMillis()))
        manager.enqueueUniqueWork(name(bookId), ExistingWorkPolicy.APPEND_OR_REPLACE, next).result.get()
    } }

    /** Release the foreground service while power is unavailable; WorkManager survives process death. */
    suspend fun waitForCharging(bookId: String, workId: String) = lock.withLock { withContext(Dispatchers.IO) {
        val current = database.processingDao().find(bookId) ?: return@withContext
        if (current.workId != workId || !ProcessingState.valueOf(current.state).active) return@withContext
        val next = request(bookId)
        database.processingDao().put(current.copy(workId = next.id.toString(), state = "QUEUED",
            message = "Waiting for charging. Progress is saved; it continues when you plug in.", updatedAt = System.currentTimeMillis()))
        manager.enqueueUniqueWork(name(bookId), ExistingWorkPolicy.REPLACE, next).result.get()
    } }

    suspend fun allowBatteryForThirtyMinutes() {
        com.example.readalong.data.preferences.AppPreferencesStore(context).update {
            it.copy(chargingOverrideUntil = System.currentTimeMillis() + 30 * 60_000L)
        }
        chargingPreferenceChanged()
    }

    suspend fun chargingPreferenceChanged() = powerChanged()

    /** Only already-authorized active books participate. Idle entries never start here. */
    suspend fun powerChanged() {
        val waiting = ChargingPolicy.shouldWait(context)
        for (job in database.processingDao().unfinished()) {
            if (waiting) {
                // Install a constrained successor before stopping its current worker/service.
                // A stale cancellation cannot overwrite the successor's work ID or checkpoint.
                if (job.message?.startsWith("Waiting for charging") != true) {
                    waitForCharging(job.bookId, job.workId)
                    withContext(Dispatchers.Main.immediate) { AudiobookTranscriptionService.pauseBook(job.bookId) }
                }
            } else if (job.message?.startsWith("Waiting for charging") == true) {
                resumeFromPower(job.bookId)
            }
        }
    }

    private suspend fun resumeFromPower(bookId: String) = lock.withLock { withContext(Dispatchers.IO) {
        if (ChargingPolicy.shouldWait(context)) return@withContext
        val current = database.processingDao().find(bookId) ?: return@withContext
        if (!ProcessingState.valueOf(current.state).active || current.message?.startsWith("Waiting for charging") != true) return@withContext
        // Expedited WorkManager work is allowed from a power broadcast even when a
        // foreground-service launch would be restricted by Android in the background.
        val next = request(bookId, expedite = true)
        database.processingDao().put(current.copy(workId = next.id.toString(), state = "QUEUED",
            message = "Plugged in. Continuing your ReadAlong…", updatedAt = System.currentTimeMillis()))
        manager.enqueueUniqueWork(name(bookId), ExistingWorkPolicy.REPLACE, next).result.get()
    } }

    /** Only one slot is armed. A completed book hands it to the next queued, unpaused book. */
    suspend fun replaceWithImportedTranscript(bookId: String, fingerprint: String, commit: suspend () -> Unit) = lock.withLock {
        check(TranscriptionPlan.fingerprint(database, bookId) == fingerprint) { "Audio changed during import. Try again." }
        database.processingDao().find(bookId)?.let { old ->
            database.processingDao().put(old.copy(state = "CANCELLED"))
        }
        withContext(Dispatchers.IO) {
            manager.cancelUniqueWork(name(bookId)).result.get()
            manager.cancelUniqueWork(recoveryName(bookId)).result.get()
        }
        withContext(Dispatchers.Main.immediate) { AudiobookTranscriptionService.pauseBook(bookId) }
        commit()
    }
    suspend fun advanceQueue() = queueCommands.withLock {
        if (database.bookDao().metadata("transcription_queue_running") != "true") return@withLock
        if (database.processingDao().unfinished().isNotEmpty()) {
            lock.withLock { withContext(Dispatchers.IO) { wakeFirst() } }
        } else {
            val waiting = database.processingDao().registeredQueue().filter { it.state in setOf("WAITING_QUEUE", "WAITING_MODEL") }
            val next = waiting.firstOrNull()
            if (next != null) prepare(next.bookId, userInitiated = false, promote = false)
            else database.bookDao().putMetadata(LibraryMetadataEntity("transcription_queue_running", "false"))
        }
    }
    private suspend fun defer(job: ProcessingJobEntity) {
        database.processingDao().put(job.copy(state = "WAITING_QUEUE",
            message = "Waiting in queue. It starts when the current book finishes.", updatedAt = System.currentTimeMillis()))
        manager.cancelUniqueWork(name(job.bookId)).result.get()
        manager.cancelUniqueWork(recoveryName(job.bookId)).result.get()
        withContext(Dispatchers.Main.immediate) { AudiobookTranscriptionService.pauseBook(job.bookId) }
    }

    suspend fun cancel(bookId: String) = lock.withLock { withContext(Dispatchers.IO) {
        database.bookDao().putMetadata(LibraryMetadataEntity("paused_transcription_notification", bookId))
        database.bookDao().putMetadata(LibraryMetadataEntity("paused_notification:$bookId", "true"))
        database.withTransaction {
            val job = database.processingDao().find(bookId) ?: return@withTransaction
            if (ProcessingState.valueOf(job.state).active) database.bookDao().putMetadata(LibraryMetadataEntity("transcription_queue_running", "false"))
            if (ProcessingState.valueOf(job.state).active || job.state in setOf("WAITING_MODEL", "WAITING_QUEUE")) database.processingDao().put(job.copy(state = "CANCELLED",
                message = "Paused. Progress so far is kept.", updatedAt = System.currentTimeMillis()))
        }
        manager.cancelUniqueWork(name(bookId)).result.get()
        manager.cancelUniqueWork(recoveryName(bookId)).result.get()
        withContext(Dispatchers.Main.immediate) { AudiobookTranscriptionService.pauseBook(bookId) }
    } }
    /** Adding enrolls a book for the next automatic handoff; it does not interrupt the current book. */
    suspend fun addToQueue(bookId: String) = lock.withLock {
        val old = database.processingDao().find(bookId)
        if (old != null && old.state != "WAITING_SELECTION") return@withLock
        val position = (database.processingDao().registeredQueue().maxOfOrNull { it.queuePosition } ?: -1) + 1
        database.processingDao().put((old ?: ProcessingJobEntity(bookId, UUID.randomUUID().toString(), SpeechModelStore.ID, "CANCELLED", 0, 0, null, 0))
            .copy(state = "WAITING_QUEUE", queuePosition = position, message = "Waiting in queue.", updatedAt = System.currentTimeMillis()))
    }
    suspend fun removeFromQueue(bookId: String) {
        cancel(bookId)
        lock.withLock {
            database.processingDao().find(bookId)?.let { job ->
                database.processingDao().put(job.copy(state = "WAITING_SELECTION", message = "Removed from the queue. Progress so far is kept.", updatedAt = System.currentTimeMillis()))
            }
            database.bookDao().removeMetadata("paused_notification:$bookId")
            if (database.bookDao().metadata("paused_transcription_notification") == bookId) database.bookDao().removeMetadata("paused_transcription_notification")
            context.getSystemService(android.app.NotificationManager::class.java).cancel(com.example.readalong.data.transcription.TranscriptionNotifications.notificationId(bookId))
        }
    }
    /**
     * Trash on the player: stops this book, deletes everything made from its audio (text, timing,
     * corrections, EPUB audio matching) and takes it out of the queue, so it's "not transcribed"
     * again. Audio, an added EPUB's own text and the listening position are kept.
     */
    suspend fun removeTranscript(bookId: String) {
        cancel(bookId)
        lock.withLock { withContext(Dispatchers.IO) {
            database.withTransaction {
                database.readingDao().clearTranscript(bookId)
                database.readingDao().clearGenerated(bookId)
                database.readingDao().clearCorrections(bookId)
                database.importedEpubDao().find(bookId)?.let { edition -> database.importedEpubDao().put(edition.copy(state = "WAITING_TRANSCRIPT", alignedRevision = -1,
                    matchedSegments = 0, totalSegments = 0, matchedTokens = 0, totalTokens = 0, message = "Make a ReadAlong to match this EPUB to the audio.", updatedAt = System.currentTimeMillis())) }
                database.importedEpubDao().clearAlignment(bookId)
                database.readingDao().findVersion(bookId)?.let { version -> database.readingDao().putVersion(version.copy(state = "PARTIAL_READY", message = null,
                    completedChapters = 0, generatedUri = null, generatedRevision = -1, transcriptRevision = version.transcriptRevision + 1, updatedAt = System.currentTimeMillis())) }
                database.processingDao().delete(bookId)
            }
            listOf("paused_notification:$bookId", "transcription_estimate:$bookId", "keep_queue_order:$bookId").forEach { database.bookDao().removeMetadata(it) }
            if (database.bookDao().metadata("paused_transcription_notification") == bookId) database.bookDao().removeMetadata("paused_transcription_notification")
            context.getSystemService(android.app.NotificationManager::class.java).cancel(com.example.readalong.data.transcription.TranscriptionNotifications.notificationId(bookId))
        } }
    }
    suspend fun reorder(bookIds: List<String>) = lock.withLock {
        database.withTransaction {
            val current = database.processingDao().registeredQueue().filter { it.state != "READY" }.map { it.bookId }
            require(bookIds.size == current.size && bookIds.toSet() == current.toSet()) { "The queue changed. Try moving the book again." }
            bookIds.forEachIndexed { index, id -> database.processingDao().position(id, index.toLong()) }
        }
    }
    suspend fun recover() {
        // Migrate old simultaneous jobs into waiting entries, retaining all saved chunks and order.
        if (database.processingDao().unfinished().isNotEmpty()) database.bookDao().putMetadata(LibraryMetadataEntity("transcription_queue_running", "true"))
        lock.withLock { withContext(Dispatchers.IO) {
            database.processingDao().orderedQueue().filter { ProcessingState.valueOf(it.state).active }.drop(MAX_ACTIVE).forEach { defer(it) }
            database.processingDao().registeredQueue().filter { it.state == "CANCELLED" &&
                database.bookDao().metadata("paused_notification:${it.bookId}") != "true" }.forEach {
                database.processingDao().put(it.copy(state = "WAITING_QUEUE"))
            }
        } }
        for (job in database.processingDao().unfinished()) {
            if (database.bookDao().findBook(job.bookId)?.isDemo == false && model.find(job.engineId) == null) {
                lock.withLock {
                    val latest = database.processingDao().find(job.bookId)
                    if (latest != null && model.find(latest.engineId) == null && ProcessingState.valueOf(latest.state).active) {
                        database.processingDao().put(latest.copy(state = "CANCELLED",
                            message = "ReadAlong has a new speech model. Resume to make this book's ReadAlong with it; your listening position is kept.", updatedAt = System.currentTimeMillis()))
                        withContext(Dispatchers.IO) { manager.cancelUniqueWork(name(job.bookId)).result.get() }
                        withContext(Dispatchers.Main.immediate) { AudiobookTranscriptionService.pauseBook(job.bookId) }
                    }
                }
                continue
            }
            val work = withContext(Dispatchers.IO) { manager.getWorkInfoById(UUID.fromString(job.workId)).get() }
            if (!AudiobookTranscriptionService.running(job.bookId, job.workId) && (work == null || work.state.isFinished)) {
                // Explicit app pauses already have CANCELLED state. An active orphan retains authorization.
                if (database.bookDao().findBook(job.bookId)?.isDemo == true) prepare(job.bookId, recoverMissing = true)
                else recoverInterrupted(job.bookId, job.workId)
            }
        }
        powerChanged()
        modelInstalled()
        advanceQueue()
    }
    companion object { const val MAX_ACTIVE = 1 }

}

class TranscriptionLimitException : IllegalStateException("One book is transcribed at a time. Others wait in the queue.")
