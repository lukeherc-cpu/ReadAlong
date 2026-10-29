package com.example.readalong.desktop.transcription

import com.example.readalong.desktop.core.AppPaths
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Downloads Whisper models into %APPDATA%\ReadAlong\models. [downloads] maps a model id to its progress (0..1). */
class ModelStore {
    private val mutableDownloads = MutableStateFlow(emptyMap<String, Float>())
    val downloads: StateFlow<Map<String, Float>> = mutableDownloads
    private val mutableInstalled = MutableStateFlow(installedIds())
    val installed: StateFlow<Set<String>> = mutableInstalled

    fun file(model: SpeechModel) = File(AppPaths.models, model.fileName)
    fun isInstalled(model: SpeechModel) = file(model).let { it.isFile && it.length() > 1_000_000 }
    private fun installedIds() = SpeechModels.all.filter(::isInstalled).map { it.id }.toSet()

    fun delete(model: SpeechModel) { file(model).delete(); mutableInstalled.value = installedIds() }

    suspend fun download(model: SpeechModel): File = withContext(Dispatchers.IO) {
        val target = file(model)
        if (isInstalled(model)) return@withContext target
        val partial = File(target.parentFile, target.name + ".part")
        mutableDownloads.update { it + (model.id to 0f) }
        try {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build()
            val response = client.send(HttpRequest.newBuilder(URI(model.url)).header("User-Agent", "ReadAlong-Desktop").build(), HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) throw java.io.IOException("The model download failed (HTTP ${response.statusCode()}). Check your connection and try again.")
            val length = response.headers().firstValueAsLong("content-length").orElse(model.sizeBytes)
            var received = 0L
            var reported = 0L
            response.body().use { input ->
                partial.outputStream().buffered(1 shl 20).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        output.write(buffer, 0, n); received += n
                        if (received - reported > 1 shl 20) { reported = received; mutableDownloads.update { it + (model.id to received.toFloat() / length) } }
                    }
                }
            }
            if (received != length) throw java.io.IOException("The model download was cut short. Try again.")
            // whisper.cpp models start with the "ggml" magic (0x67676d6c, little-endian).
            partial.inputStream().use { val magic = it.readNBytes(4); require(magic.contentEquals(byteArrayOf(0x6c, 0x6d, 0x67, 0x67))) { "The downloaded file isn't a Whisper model." } }
            java.nio.file.Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            mutableInstalled.value = installedIds()
            target
        } finally {
            partial.delete()
            mutableDownloads.update { it - model.id }
        }
    }
}
