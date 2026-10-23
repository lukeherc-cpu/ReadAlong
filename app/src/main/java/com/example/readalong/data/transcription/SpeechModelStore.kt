package com.example.readalong.data.transcription

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.readalong.domain.transcription.SpeechModels
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

private val recoverModelsKey = booleanPreferencesKey("recover_models")
private val Context.speechModelPreferences by preferencesDataStore("speech_model", corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { mutablePreferencesOf(recoverModelsKey to true) })
data class ModelStatus(val state: String = "MISSING", val bytes: Long = 0, val message: String? = null, val installed: Boolean = false, val modelId: String = SpeechModels.whistle.id)

class SpeechModelStore(private val context: Context, private val data: androidx.datastore.core.DataStore<Preferences> = context.speechModelPreferences) {
    private val publication = Mutex()
    private val readers = Semaphore(3)
    private val installation = Mutex()
    private val recovery = Mutex()
    private val stateKey = stringPreferencesKey("state")
    private val bytesKey = longPreferencesKey("bytes")
    private val messageKey = stringPreferencesKey("message")
    private val selectionKey = stringPreferencesKey("selected_model")
    private val installedKey = booleanPreferencesKey("installed:$ID")
    private val dismissedKey = booleanPreferencesKey("whistle_setup_dismissed")
    private val directory get() = File(context.filesDir, "speech-models").apply { mkdirs() }
    private val modelFile get() = File(directory, SpeechModels.whistle.fileName)
    private suspend fun recoverIfNeeded() = recovery.withLock {
        if (data.data.first()[recoverModelsKey] == true) {
            val recovered = recoverModelPreferences(context)
            data.edit { p -> p.clear(); recovered.asMap().forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                p[key as Preferences.Key<Any>] = value
            } }
        }
    }
    suspend fun find(modelId: String) = SpeechModels.find(modelId)
    suspend fun spec(modelId: String) = SpeechModels.require(modelId)
    suspend fun selectedModel() = SpeechModels.whistle.also { recoverIfNeeded() }
    suspend fun migrateLegacySetup(): Boolean {
        recoverIfNeeded()
        var changed = false
        data.edit { p ->
            if (p[selectionKey] != null && p[selectionKey] != ID) {
                changed = true; p[selectionKey] = ID; p[stateKey] = "MISSING"; p[bytesKey] = 0; p.remove(messageKey)
            }
        }
        return changed
    }
    suspend fun select(modelId: String) {
        SpeechModels.require(modelId)
        val installed = available(modelId)
        data.edit { it[selectionKey] = ID; it[stateKey] = if (installed) "READY" else "MISSING"; it[bytesKey] = 0; it.remove(messageKey) }
    }
    val setupDismissed = data.data.map { it[dismissedKey] ?: false }
    suspend fun dismissSetup() = data.edit { it[dismissedKey] = true }
    suspend fun needsAutomaticSetup(): Boolean = !available() && data.data.first()[booleanPreferencesKey("automatic_download_disabled")] != true
    suspend fun automaticDownloads(enabled: Boolean) = data.edit { it[booleanPreferencesKey("automatic_download_disabled")] = !enabled }
    val status = data.data.catch { failure -> if (failure is java.io.IOException) emit(emptyPreferences()) else throw failure }.map { original ->
        val p = if (original[recoverModelsKey] == true) { recoverIfNeeded(); data.data.first() } else original
        val installed = p[installedKey] == true && modelFile.length() == SpeechModels.whistle.size
        val current = p[selectionKey] == ID
        ModelStatus(if (current) p[stateKey] ?: "MISSING" else if (installed) "READY" else "MISSING",
            if (current) p[bytesKey] ?: 0 else 0, if (current) p[messageKey] else null, installed, ID)
    }
    suspend fun available(modelId: String? = null): Boolean {
        if (modelId != null && modelId != ID) return false
        recoverIfNeeded()
        return data.data.first()[installedKey] == true && modelFile.length() == SpeechModels.whistle.size
    }
    suspend fun <T> withModel(modelId: String = ID, block: suspend (File) -> T): T = readers.withPermit {
        if (!available(modelId)) throw ModelUnavailableException("Install Cactus Whistle, then rebuild books using an older engine.")
        block(modelFile)
    }
    suspend fun report(state: String, bytes: Long, message: String?, modelId: String? = null) = data.edit { p ->
        if (modelId != null && modelId != ID) return@edit
        p[selectionKey] = ID; p[stateKey] = state; p[bytesKey] = bytes
        if (message == null) p.remove(messageKey) else p[messageKey] = message
    }
    suspend fun install(input: InputStream, modelId: String = ID) = installation.withLock { withContext(Dispatchers.IO) {
        val spec = SpeechModels.require(modelId)
        check(directory.usableSpace > spec.size + 32L * 1024 * 1024) { "Not enough free storage to install Whistle." }
        val temporary = File(directory, "model-${java.util.UUID.randomUUID()}.part")
        val pcm = File(context.cacheDir, "${temporary.name}-probe.pcm")
        try {
            val digest = MessageDigest.getInstance("SHA-256"); var count = 0L; var reported = 0L
            report("INSTALLING", 0, "Installing Cactus Whistle…", ID)
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    ensureActive(); val n = input.read(buffer); if (n < 0) break
                    count += n; check(count <= spec.size) { "This isn't the supported Cactus Whistle model." }
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                    if (count - reported >= 1024 * 1024) { report("INSTALLING", count, "Installing Cactus Whistle…", ID); reported = count }
                }
            }
            check(count == spec.size && digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256) { "Whistle verification failed. Retry the download." }
            report("INSTALLING", count, "Checking Whistle on this device…", ID)
            pcm.writeBytes(ByteArray(16000 * 4))
            RecognitionConnection(context, ModelValidationService::class.java).transcribe(temporary, pcm)
            ensureActive()
            publication.withLock {
                var acquired = 0
                try {
                    repeat(3) { readers.acquire(); acquired++ }
                    withContext(NonCancellable) {
                check(temporary.renameTo(modelFile)) { "Couldn't save Whistle. Check free storage." }
                data.edit { p -> p[installedKey] = true; p[selectionKey] = ID; p[stateKey] = "READY"; p[bytesKey] = spec.size
                    p[messageKey] = "Cactus Whistle is installed. Recognition runs offline." }
                    }
                } finally { repeat(acquired) { readers.release() } }
            }
        } finally { temporary.delete(); pcm.delete() }
    } }
    suspend fun import(uri: Uri, modelId: String = ID) = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri).use { input -> install(requireNotNull(input) { "Couldn't open Whistle model." }, modelId) }
    }
    suspend fun cleanAbandonedImports() {
        recoverIfNeeded()
        installation.withLock { withContext(Dispatchers.IO) {
            directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 && it.extension == "part" }?.forEach {
                if (it.isDirectory) it.deleteRecursively() else it.delete()
            }
        } }
    }
    suspend fun remove() = installation.withLock { publication.withLock {
        var acquired = 0
        try {
            repeat(3) { readers.acquire(); acquired++ }
            withContext(Dispatchers.IO) {
        check(!modelFile.exists() || modelFile.delete()) { "Couldn't remove Whistle." }
        data.edit { p -> p[installedKey] = false; p[stateKey] = "MISSING"; p[bytesKey] = 0; p.remove(messageKey) }
            }
        } finally { repeat(acquired) { readers.release() } }
    } }
    companion object { const val ID = "cactus-whistle-v2-b6e02f04" }
}
internal suspend fun recoverModelPreferences(context: Context): Preferences = withContext(Dispatchers.IO) {
    val p = mutablePreferencesOf()
    val spec = SpeechModels.whistle
    val file = File(context.filesDir, "speech-models/${spec.fileName}")
    val verified = file.length() == spec.size && runCatching { file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256
    } }.getOrDefault(false)
    p[booleanPreferencesKey("installed:${spec.id}")] = verified
    p[stringPreferencesKey("selected_model")] = spec.id
    p[stringPreferencesKey("state")] = if (verified) "READY" else "MISSING"
    p[stringPreferencesKey("message")] = "Model settings were recovered. Books and saved files were kept."
    p
}
class ModelUnavailableException(message: String) : IllegalStateException(message)
