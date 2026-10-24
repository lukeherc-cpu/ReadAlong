package com.example.readalong.data.transcription

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One durable download; no activity, setup dialog or notification permission request required. */
object AutomaticModelDownload {
    private val lock = Mutex()
    suspend fun ensure(context: Context, store: SpeechModelStore, requestedForBook: Boolean = false) = lock.withLock {
        withContext(Dispatchers.IO) {
            store.migrateLegacySetup()
            if (requestedForBook) store.automaticDownloads(true)
            if (!store.needsAutomaticSetup()) return@withContext
            val manager = WorkManager.getInstance(context)
            if (manager.getWorkInfosForUniqueWork(ModelInstallWorker.WORK_NAME).get().any { !it.state.isFinished }) return@withContext
            store.report("QUEUED", 0, "The speech model will download when you're online.")
            manager.enqueueUniqueWork(ModelInstallWorker.WORK_NAME, ExistingWorkPolicy.KEEP,
                ModelInstallWorker.request(automatic = true)).result.get()
        }
    }
}
