package com.example.readalong.desktop.transcription

import com.example.readalong.compat.*
import com.example.readalong.desktop.audio.AudioReader
import com.example.readalong.desktop.platform.keepScreenOn
import java.io.File
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.*
import kotlin.concurrent.AtomicInt
import org.json.JSONObject

/**
 * [processDied]: the transcriber stopped (on iOS: it was closed or cancelled).
 * [recognition]: whisper itself failed on the audio it was given; the transcriber still works.
 */
class TranscriberException(message: String, val processDied: Boolean = false, val recognition: Boolean = false) : Exception(message)

/**
 * The desktop's transcriber process, inside the app: iOS doesn't let an app start processes. Each instance has its
 * own thread and its own whisper.cpp model, and keeps the desktop's API, so TranscriptionService runs unchanged.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class TranscriberProcess private constructor() : AutoCloseable {
    lateinit var devices: List<Device>
        private set
    var systemInfo = ""
        private set
    private val thread = newSingleThreadContext("readalong-transcriber-${counter.incrementAndGet()}")
    private var model = 0L
    @kotlin.concurrent.Volatile private var dead = false
    private val log = ArrayDeque<String>()

    fun recentLog(): List<String> = synchronized(log) { log.toList() }
    fun drainLog(): List<String> = recentLog()
    private fun note(line: String) = synchronized(log) { log.addLast(line); while (log.size > 80) log.removeFirst() }

    private fun checkAlive() { if (dead) throw TranscriberException("The transcriber stopped.", processDied = true) }

    /** Loads [model]; returns the device it runs on ("CPU" or the GPU's name) and whether that's a GPU. */
    suspend fun load(model: File, heads: Int, device: String): Pair<String, Boolean> = withContext(thread) {
        checkAlive()
        val whisper = WhisperNative.require()
        if (this@TranscriberProcess.model != 0L) { whisper.free(this@TranscriberProcess.model); this@TranscriberProcess.model = 0 }
        if (!model.isFile) throw TranscriberException("The speech model file is missing. Download it again in Settings.")
        val gpu = SpeechModels.gpuIndex(devices, device)
        var handle = whisper.load(model.absolutePath, gpu, heads)
        var onGpu = gpu >= 0
        if (handle == 0L && gpu >= 0) { handle = whisper.load(model.absolutePath, -1, heads); onGpu = false }
        if (handle == 0L) throw TranscriberException("The speech model couldn't be loaded. Download it again in Settings.")
        this@TranscriberProcess.model = handle
        val name = if (onGpu) devices.filter { it.isGpu }.getOrNull(gpu)?.label ?: "GPU" else "CPU"
        note("loaded ${model.name} on $name")
        name to onGpu
    }

    /** Transcribes [units] (file-relative [start, end] ms) of one file in order, decoding it once from the first unit. */
    suspend fun transcribe(file: File, units: List<Pair<Long, Long>>, language: String, threads: Int, beams: Int,
        onProgress: (unit: Int, percent: Int) -> Unit, onUnit: suspend (index: Int, result: JSONObject, ms: Long) -> Unit) {
        if (units.isEmpty()) return
        checkAlive()
        val whisper = WhisperNative.require()
        val handle = model.takeIf { it != 0L } ?: throw TranscriberException("No speech model is loaded.")
        val rate = 16_000
        active.incrementAndGet(); keepScreenOn(true)
        try {
            withContext(thread) {
                AudioReader(file, rate, 1, floats = true).use { reader ->
                    if (units.first().first > 0) reader.seek(units.first().first)
                    var buffer = FloatArray(0)
                    var buffered = 0
                    var bufferStartMs = units.first().first
                    for ((index, unit) in units.withIndex()) {
                        checkAlive()
                        val (start, end) = unit
                        val dropSamples = ((start - bufferStartMs) * rate / 1000).toInt().coerceIn(0, buffered)
                        if (dropSamples > 0) { buffer.copyInto(buffer, 0, dropSamples, buffered); buffered -= dropSamples; bufferStartMs = start }
                        val need = ((end - start) * rate / 1000).toInt()
                        while (buffered < need) {
                            val samples = reader.readFloats() ?: break
                            if (buffer.size < buffered + samples.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, buffered + samples.size, need + rate))
                            samples.copyInto(buffer, buffered); buffered += samples.size
                        }
                        val take = minOf(need, buffered)
                        val audio = buffer.copyOf(take)
                        val began = System.nanoTime()
                        val reporter = GlobalScope.launch(Dispatchers.Default) {
                            while (isActive) { delay(250); onProgress(index, whisper.progress(handle)) }
                        }
                        val json = try {
                            if (audio.isEmpty()) """{"language":null,"segments":[]}"""
                            else audio.usePinned { whisper.transcribe(handle, it.addressOf(0).toLong(), audio.size, language, threads, beams) }
                        } finally { reporter.cancel() }
                        checkAlive()
                        if (json == null) throw TranscriberException("Speech recognition failed", recognition = true)
                        val parsed = JSONObject(json)
                        val result = TranscriptStore.chunkResult(parsed.getJSONArray("segments").toString(), end - start)
                        onUnit(index, result, (System.nanoTime() - began) / 1_000_000)
                        buffer.copyInto(buffer, 0, take, buffered); buffered -= take; bufferStartMs = end
                    }
                }
            }
        } finally {
            if (active.decrementAndGet() == 0) keepScreenOn(false)
        }
    }

    override fun close() {
        if (dead) return
        dead = true
        val handle = model; model = 0
        if (handle != 0L) {
            WhisperNative.engine?.cancel(handle)
            // Freed on its own thread, after any recognition there has stopped.
            GlobalScope.launch(thread) { WhisperNative.engine?.free(handle); thread.close() }
        } else thread.close()
    }
    fun kill() = close()

    companion object {
        private val counter = AtomicInt(0)
        private val active = AtomicInt(0)
        private var initialized = false

        suspend fun start(): TranscriberProcess = withContext(Dispatchers.Default) {
            val whisper = WhisperNative.engine ?: throw TranscriberException("whisper.cpp isn't part of this build of ReadAlong.", processDied = true)
            TranscriberProcess().also { p ->
                p.devices = Worker.parseDevices(whisper.devices())
                p.systemInfo = whisper.systemInfo()
            }
        }
    }
}
