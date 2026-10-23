package com.example.readalong.data.processing

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.domain.transcription.ProcessingState
import com.example.readalong.domain.transcription.SpeechModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.UUID

data class PreparationExecution(val headline: String, val canStartNow: Boolean, val diagnostics: String, val secondsSinceUpdate: Long = 0)

/** Observed only while its screen is visible. Report OS scheduling separately from saved transcription progress. */
fun preparationExecution(context: Context, database: ReadAlongDatabase, bookId: String) = flow {
    val manager = WorkManager.getInstance(context)
    while (true) {
        val job = database.processingDao().find(bookId)
        if (job == null) emit(null)
        else {
            val work = manager.getWorkInfoById(UUID.fromString(job.workId)).get()
            val serviceRunning = AudiobookTranscriptionService.running(bookId, job.workId)
            val active = ProcessingState.valueOf(job.state).active
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val powerSave = context.getSystemService(PowerManager::class.java).isPowerSaveMode
            val restricted = Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
            val next = database.processingDao().nextBook()
            val headline = when {
                serviceRunning -> job.message ?: "Transcribing audiobook…"
                !active -> job.message ?: "Choose this book to prepare its text."
                work?.state == WorkInfo.State.RUNNING && job.state == "PROCESSING" -> job.message ?: "Preparing audio…"
                job.message?.startsWith("Waiting for charging") == true -> job.message
                next != null && next != bookId -> "Waiting for the book ahead in your queue."
                work == null || work.state.isFinished -> "Scheduling stopped. Tap Start now to resume from saved progress."
                work.state == WorkInfo.State.RUNNING -> job.message ?: "Starting preparation…"
                work.state == WorkInfo.State.BLOCKED -> "Finishing the preceding audio section before continuing."
                work.constraints.requiresBatteryNotLow() && percent in 0..15 && !plugged -> "Waiting for battery to charge. Start now can prepare the next section on Android 12 or newer."
                restricted -> "Android is restricting background work. Open app battery settings to allow background use."
                powerSave -> "Waiting for Android while Battery Saver is on. Start now requests priority."
                work.runAttemptCount > 0 -> "Waiting to retry this audio section. Start now requests priority."
                else -> "Waiting for Android to start. Tap Start now to request priority."
            }
            val model = SpeechModels.find(job.engineId)?.name ?: job.engineId
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            val recentFailure = database.bookDao().metadata("preparation_failure:$bookId").orEmpty()
            val exits = if (Build.VERSION.SDK_INT >= 30) runCatching {
                context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 4)
                    .joinToString("\n") { "${it.processName}: reason ${it.reason}; status ${it.status}; memory ${it.pss} KB; ${it.timestamp}" }
            }.getOrDefault("") else "Unavailable"
            val diagnostics = "ReadAlong $version transcription diagnostics\n" +
                "Device: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "Model: $model\nSaved state: ${job.state}; completed ${job.completedUnits}/${job.totalUnits}\n" +
                "Last saved update: ${(System.currentTimeMillis() - job.updatedAt).coerceAtLeast(0) / 1000}s ago\n" +
                "Foreground transcription: $serviceRunning\n" +
                "Worker: ${work?.state ?: "MISSING"}; attempts ${work?.runAttemptCount ?: 0}; stop reason ${work?.stopReason ?: -1}\n" +
                "Battery: $percent%; plugged $plugged; saver $powerSave; background restricted $restricted\n" +
                "Status: $headline\nLast processing failure: $recentFailure\nRecent process exits:\n$exits\nFree storage: ${context.filesDir.usableSpace / 1_048_576} MB\nLast captured crash:\n${com.example.readalong.data.diagnostics.CrashDiagnostics.read(context)}"
            emit(PreparationExecution(headline, active && !serviceRunning && work?.state != WorkInfo.State.RUNNING && !ChargingPolicy.shouldWait(context), diagnostics,
                (System.currentTimeMillis() - job.updatedAt).coerceAtLeast(0) / 1000))
        }
        delay(2_000)
    }
}.flowOn(Dispatchers.IO)
