package com.example.readalong.data.transcription

import android.content.Context
import android.net.Uri
import android.content.pm.ServiceInfo
import androidx.work.*
import com.example.readalong.ReadAlongApplication
import kotlinx.coroutines.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class ModelInstallWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = (applicationContext as ReadAlongApplication).speechModel
        val modelId = inputData.getString("modelId") ?: SpeechModelStore.ID
        val spec = com.example.readalong.domain.transcription.SpeechModels.find(modelId) ?: return@withContext Result.failure()
        try {
            if (inputData.getString("uri") == null && store.available(spec.id)) {
                (applicationContext as ReadAlongApplication).processing.modelInstalled()
                return@withContext Result.success()
            }
            setForeground(SpeechNotifications.foreground(applicationContext, id, "Installing ${spec.name}", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            val source = inputData.getString("uri")
            if (source != null) store.import(Uri.parse(source), spec.id)
            else {
                store.report("INSTALLING", 0, "Downloading ${spec.name}. Audiobook audio stays on this device.", spec.id)
                openDownload(spec.url).use { store.install(it, spec.id) }
            }
            (applicationContext as ReadAlongApplication).processing.modelInstalled()
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { store.report("CANCELLED", 0, "Model installation stopped. Retry to install it.", spec.id) }
            throw cancelled
        } catch (failure: java.io.IOException) {
            if (inputData.getString("uri") != null) {
                store.report("FAILED", 0, "Couldn't read the selected model file. Choose it again from Offline speech models.", spec.id)
                Result.failure()
            } else {
                store.report("QUEUED", 0, "Whistle download will retry when the connection is available.", spec.id)
                Result.retry()
            }
        } catch (failure: Exception) {
            store.report("FAILED", 0, if (failure is IllegalStateException) failure.message else "Couldn't install the model. Check the connection and free storage, then retry.", spec.id)
            Result.failure()
        }
    }
    companion object {
        const val WORK_NAME = "install-whisper-base"
        fun request(uri: Uri? = null, automatic: Boolean = false, modelId: String = SpeechModelStore.ID) = OneTimeWorkRequestBuilder<ModelInstallWorker>()
            .setInputData(workDataOf("uri" to uri?.toString(), "modelId" to modelId))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).apply {
                if (uri == null) setRequiredNetworkType(NetworkType.CONNECTED)
            }.build()).build()
    }
}

/** Own the connection for exactly the stream lifetime, including validation or cancellation failures. */
private fun openDownload(url: String): java.io.InputStream {
    require(URL(url).protocol == "https")
    val connection = URL(url).openConnection() as HttpsURLConnection
    connection.connectTimeout = 30_000; connection.readTimeout = 30_000
    try {
        if (connection.responseCode == 408 || connection.responseCode == 429 || connection.responseCode >= 500)
            throw java.io.IOException("Temporary model download failure.")
        check(connection.responseCode == 200) { "Model download failed. Retry when a connection is available." }
        check(connection.url.protocol == "https")
        return object : java.io.FilterInputStream(connection.inputStream) {
            override fun close() { try { super.close() } finally { connection.disconnect() } }
        }
    } catch (failure: Exception) { connection.disconnect(); throw failure }
}
