package com.example.readalong.ui.transcription

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.*
import androidx.work.*
import com.example.readalong.data.transcription.*
import com.example.readalong.data.processing.ProcessingRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

class ModelViewModel(private val context: Context, private val store: SpeechModelStore, private val processing: ProcessingRepository) : ViewModel() {
    private val manager = WorkManager.getInstance(context)
    val status = store.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ModelStatus(state = "LOADING"))
    val busy = manager.getWorkInfosForUniqueWorkFlow(ModelInstallWorker.WORK_NAME).map { jobs -> jobs.any { !it.state.isFinished } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    val setupDismissed = store.setupDismissed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    private val scheduling = kotlinx.coroutines.sync.Mutex()
    fun dismissSetup() = action { store.dismissSetup() }
    fun download() = enqueue(null)
    // Selection alone never downloads a model. Download is a separate, explicit user action.
    fun chooseModel(modelId: String) = action { scheduling.withLock { withContext(Dispatchers.IO) {
        if (store.selectedModel().id != modelId) {
            manager.cancelUniqueWork(ModelInstallWorker.WORK_NAME).result.get()
            store.select(modelId)
        }
    } } }
    fun import(uri: Uri) = action {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        schedule(uri)
    }
    private fun enqueue(uri: Uri?) = action { schedule(uri) }
    private suspend fun schedule(uri: Uri?, automatic: Boolean = false, modelId: String? = null) = scheduling.withLock { withContext(Dispatchers.IO) {
        store.automaticDownloads(true)
        val old = store.selectedModel()
        val spec = modelId?.let(com.example.readalong.domain.transcription.SpeechModels::require) ?: old
        if (spec.id != old.id) {
            manager.cancelUniqueWork(ModelInstallWorker.WORK_NAME).result.get()
            store.select(spec.id)
        }
        if (uri == null && store.available(spec.id)) return@withContext
        val running = manager.getWorkInfosForUniqueWork(ModelInstallWorker.WORK_NAME).get().any { it.state == WorkInfo.State.RUNNING }
        if (running && spec.id == old.id) return@withContext
        val request = ModelInstallWorker.request(uri, automatic, spec.id)
        store.report("QUEUED", 0, if (automatic) "The speech model will download on Wi-Fi (${spec.size / 1_000_000 + 1} MB), or tap Download now." else "Download starting (${spec.size / 1_000_000 + 1} MB)…")
        manager.enqueueUniqueWork(ModelInstallWorker.WORK_NAME, if (automatic) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request).result.get()
    } }
    fun cancel() = action {
        store.automaticDownloads(false)
        withContext(Dispatchers.IO) { manager.cancelUniqueWork(ModelInstallWorker.WORK_NAME).result.get() }
        store.report("CANCELLED", 0, "Download cancelled.")
    }
    fun remove() = action {
        store.automaticDownloads(false)
        withContext(Dispatchers.IO) { manager.cancelUniqueWork(ModelInstallWorker.WORK_NAME).result.get() }
        processing.cancelRealJobs(store.selectedModel().id)
        store.remove()
    }
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { mutableError.value = null; block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableError.value = failure.message ?: "Couldn't change the speech model. Check free space and try again." }
        }
    }
    companion object {
        fun factory(context: Context, store: SpeechModelStore, processing: ProcessingRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ModelViewModel(context.applicationContext, store, processing) as T
        }
    }
}
