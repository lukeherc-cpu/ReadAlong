package com.example.readalong.desktop.transcription

import com.example.readalong.desktop.core.AppPaths
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Downloads Whisper models into %APPDATA%\ReadAlong\models. [downloads] maps a model id to its progress (0..1). */
class ModelStore(private val directory: File = AppPaths.models,
    /** A download that delivers nothing for this long is given up (and carries on from there at the next try). */
    private val stallMs: Long = 60_000) {
    private val mutableDownloads = MutableStateFlow(emptyMap<String, Float>())
    val downloads: StateFlow<Map<String, Float>> = mutableDownloads
    private val mutableInstalled = MutableStateFlow(installedIds())
    val installed: StateFlow<Set<String>> = mutableInstalled

    fun file(model: SpeechModel) = File(directory, model.fileName)
    fun isInstalled(model: SpeechModel) = file(model).let { it.isFile && it.length() > 1_000_000 }
    private fun installedIds() = SpeechModels.all.filter(::isInstalled).map { it.id }.toSet()

    fun delete(model: SpeechModel) { file(model).delete(); mutableInstalled.value = installedIds() }

    // One download per model at a time: the background download of Tiny and a book that needs it share one file.
    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /**
     * Keeps the default model (Tiny for the chosen language) on this computer, so the first book starts without a
     * manual download. Tries again every few minutes while offline; quiet, since nothing is waiting on it yet.
     */
    fun keepDefault(scope: CoroutineScope, language: kotlinx.coroutines.flow.Flow<String>) = scope.launch {
        language.distinctUntilChanged().collectLatest { code ->
            val model = SpeechModels.default(code)
            var wait = 30_000L
            while (!isInstalled(model)) {
                if (runCatching { download(model) }.isSuccess) break
                delay(wait); wait = (wait * 2).coerceAtMost(15 * 60_000L)
            }
        }
    }

    /** [from]: where the model is fetched from (tests point it at a local server). */
    suspend fun download(model: SpeechModel, from: String = model.url): File = locks.getOrPut(model.id) { kotlinx.coroutines.sync.Mutex() }.withLock { downloadNow(model, from) }

    private suspend fun downloadNow(model: SpeechModel, from: String): File = withContext(Dispatchers.IO) {
        val target = file(model)
        if (isInstalled(model)) return@withContext target
        // Kept between attempts: a download that was cut off carries on from where it stopped.
        val partial = File(target.parentFile, target.name + ".part")
        mutableDownloads.update { it + (model.id to 0f) }
        try {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20)).build()
            var uri = URI(from)
            var expectedSha: String? = null
            var response: HttpResponse<java.io.InputStream>
            var have = partial.takeIf { it.isFile }?.length() ?: 0L
            var hops = 0
            while (true) {
                val request = HttpRequest.newBuilder(uri).header("User-Agent", "ReadAlong-Desktop").apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                val status = response.statusCode()
                if (status in setOf(301, 302, 303, 307, 308) && hops++ < 8) {
                    // Hugging Face names the file's SHA-256 on the redirect to its storage.
                    expectedSha = expectedSha ?: sha256Header(response.headers().firstValue("x-linked-etag").orElse(null))
                    val location = response.headers().firstValue("location").orElse(null) ?: throw java.io.IOException("The model download failed (no address). Try again.")
                    runCatching { response.body().close() }
                    uri = uri.resolve(location)
                    continue
                }
                // The server no longer has that range (the file changed, or the part is already whole): start over.
                if (status == 416 && have > 0) { runCatching { response.body().close() }; partial.delete(); have = 0; continue }
                if (status != 200 && status != 206) throw java.io.IOException("The model download failed (HTTP $status). Check your connection and try again.")
                break
            }
            val resumed = response.statusCode() == 206
            if (!resumed) have = 0
            val length = if (resumed) response.headers().firstValue("content-range").orElse("").substringAfterLast('/').toLongOrNull() ?: (have + response.headers().firstValueAsLong("content-length").orElse(model.sizeBytes - have))
                else response.headers().firstValueAsLong("content-length").orElse(model.sizeBytes)
            var received = have
            var reported = have
            mutableDownloads.update { it + (model.id to (received.toFloat() / length).coerceIn(0f, 1f)) }
            response.body().use { input ->
                // A connection that goes quiet without closing would otherwise wait forever: closing the stream ends the read.
                val lastRead = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
                val watchdog = launch {
                    while (isActive) {
                        delay((stallMs / 4).coerceIn(50, 5_000))
                        if ((System.nanoTime() - lastRead.get()) / 1_000_000 > stallMs) { runCatching { input.close() }; break }
                    }
                }
                try {
                    java.io.FileOutputStream(partial, resumed).buffered(1 shl 20).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (received < length) {
                            ensureActive()
                            val n = try { input.read(buffer) } catch (_: java.io.IOException) { -1 }
                            if (n < 0) break
                            lastRead.set(System.nanoTime())
                            output.write(buffer, 0, n); received += n
                            if (received - reported > 1 shl 20) { reported = received; mutableDownloads.update { it + (model.id to received.toFloat() / length) } }
                        }
                    }
                } finally { watchdog.cancel() }
            }
            if (received < length) throw java.io.IOException("The model download was cut short. Try again and it carries on from here.")
            // whisper.cpp models start with the "ggml" magic (0x67676d6c, little-endian).
            val magic = partial.inputStream().use { it.readNBytes(4) }
            val whole = received == length && magic.contentEquals(byteArrayOf(0x6c, 0x6d, 0x67, 0x67)) && (expectedSha == null || sha256(partial) == expectedSha)
            if (!whole) { partial.delete(); throw java.io.IOException("The downloaded model was damaged on the way. Try again.") }
            java.nio.file.Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            mutableInstalled.value = installedIds()
            target
        } finally {
            mutableDownloads.update { it - model.id }
        }
    }

    companion object {
        /** The SHA-256 in an `x-linked-etag` header (64 hex digits, usually in quotes), or null when it isn't one. */
        fun sha256Header(value: String?): String? = value?.trim()?.removePrefix("W/")?.trim('"')?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

        fun sha256(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(1 shl 20); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
