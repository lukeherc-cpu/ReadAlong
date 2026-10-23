package com.example.readalong.data.transcription

import android.content.Context
import android.net.Uri
import com.example.readalong.domain.transcription.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class WhistleTranscriptionEngine(private val context: Context, private val store: SpeechModelStore, override val id: String = SpeechModelStore.ID,
    private val connection: RecognitionConnection = RecognitionConnection(context),
    private val onProgress: suspend (String) -> Unit = {}) : TranscriptionEngine {
    override fun transcribe(input: TranscriptionInput) = flow {
        require(!input.isDemo && input.audioUri != null)
        val duration = input.endMs - input.startMs
        require(duration in 1..30_000)
        val directory = File(context.filesDir, "recognition").apply { mkdirs() }
        val pcm = File(directory, "${java.util.UUID.randomUUID()}.pcm")
        var phase = "Reading audio"
        try {
            onProgress("Reading audio · ${com.example.readalong.playback.clockTime(input.startMs)}–${com.example.readalong.playback.clockTime(input.endMs)}")
            val decodeBegan = android.os.SystemClock.elapsedRealtime()
            val decodedMs = AudioDecoder(context).decode(Uri.parse(input.audioUri), input.fileStartMs, duration, pcm)
            android.util.Log.d("ReadAlongPerf", "audio_ms=$decodedMs decode_ms=${android.os.SystemClock.elapsedRealtime() - decodeBegan}")
            if (decodedMs == 0L) { onProgress("Saving the empty audio interval…"); return@flow }
            phase = "Speech recognition"
            store.withModel(id) { model ->
                onProgress("Loading ${store.spec(id).name}…")
                val recognitionBegan = android.os.SystemClock.elapsedRealtime()
                val json = coroutineScope {
                    val updates = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.CONFLATED)
                    val reporter = launch {
                        var previous: Int? = null
                        for (progress in updates) if (progress != previous) {
                            previous = progress
                            onProgress(if (progress < 0) "Loading ${store.spec(id).name}…"
                                else "${store.spec(id).name} · transcribing this audio section · ${progress.coerceIn(0, 100)}%")
                        }
                    }
                    try { connection.transcribe(model, pcm, backend = store.spec(id).backend) { updates.trySend(it) } }
                    finally { updates.close(); reporter.join() }
                }
                android.util.Log.d("ReadAlongPerf", "recognition_ms=${android.os.SystemClock.elapsedRealtime() - recognitionBegan}")
                onProgress("Saving recognized text…")
                WhistleResult.segments(json, input.copy(endMs = minOf(input.endMs, input.startMs + decodedMs))).forEach { emit(it) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val explanation = when {
                failure is ModelUnavailableException -> failure.message ?: "Install this book’s speech model, then retry."
                failure is SecurityException -> "Audio access was lost. Choose the audiobook folder or files again, then retry."
                phase == "Reading audio" -> "Couldn't decode this audio section. Check that the file plays and is available locally, then rescan the folder and retry."
                else -> "Speech recognition stopped. Check that this book's model is installed and there is enough free memory, then retry."
            }
            throw SpeechPreparationException(explanation, failure)
        } finally { pcm.delete() }
    }.flowOn(Dispatchers.IO)
}

class SpeechPreparationException(val explanation: String, cause: Throwable) : Exception(explanation, cause)
