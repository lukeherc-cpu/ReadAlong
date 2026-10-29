package com.example.readalong.desktop.transcription

import com.example.readalong.desktop.audio.AudioReader
import com.example.readalong.desktop.core.AppPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintStream
import kotlin.concurrent.thread

/**
 * The transcriber process (ReadAlong started with --transcriber). Whisper and the graphics driver run
 * here, not in the app: a driver fault or running out of GPU memory ends this process, and the app
 * carries on and restarts it on the CPU. Commands arrive as JSON lines on stdin; replies are JSON lines
 * on stdout prefixed with [PREFIX] (native libraries may print other lines).
 */
object Worker {
    const val PREFIX = "@@ "
    private lateinit var out: PrintStream
    private var model = 0L
    private var devices = emptyList<Device>()

    private fun send(message: JSONObject) = synchronized(this) { out.println(PREFIX + message); out.flush() }

    fun run() {
        out = PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8")
        System.setOut(System.err) // Only protocol lines on stdout.
        try {
            val directory = requireNotNull(AppPaths.whisperLibraries) { "whisper.cpp isn't installed with this copy of ReadAlong." }
            WhisperNative.load(directory, verbose = System.getenv("READALONG_VERBOSE") != null)
            devices = parseDevices(WhisperNative.devices())
            send(JSONObject().put("type", "ready").put("devices", JSONArray(WhisperNative.devices())).put("system", WhisperNative.systemInfo()))
        } catch (failure: Throwable) {
            send(JSONObject().put("type", "error").put("message", failure.message ?: failure.toString())); return
        }
        val input = System.`in`.bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val command = JSONObject(line)
            try {
                when (command.getString("cmd")) {
                    "load" -> load(command)
                    "file" -> file(command)
                    "quit" -> break
                }
            } catch (failure: Throwable) {
                send(JSONObject().put("type", "error").put("message", failure.message ?: failure.toString()))
            }
        }
        if (model != 0L) WhisperNative.free(model)
    }

    private fun load(command: JSONObject) {
        if (model != 0L) { WhisperNative.free(model); model = 0 }
        val gpu = SpeechModels.gpuIndex(devices, command.optString("device", "auto"))
        val path = command.getString("model")
        require(File(path).isFile) { "The speech model file is missing. Download it again in Settings." }
        model = WhisperNative.load(path, gpu, command.optInt("heads"))
        var onGpu = gpu >= 0
        if (model == 0L && gpu >= 0) { model = WhisperNative.load(path, -1, command.optInt("heads")); onGpu = false }
        require(model != 0L) { "The speech model couldn't be loaded. Download it again in Settings." }
        val device = if (onGpu) devices.filter { it.isGpu }.getOrNull(gpu) else null
        send(JSONObject().put("type", "loaded").put("gpu", onGpu).put("device", device?.label ?: "CPU"))
    }

    /** Transcribes [units] (file-relative [start, end] ms) of one file in order, decoding it once from the first unit. */
    private fun file(command: JSONObject) {
        require(model != 0L) { "No speech model is loaded." }
        val units = command.getJSONArray("units").let { a -> List(a.length()) { a.getJSONArray(it).let { u -> u.getLong(0) to u.getLong(1) } } }
        if (units.isEmpty()) return
        val language = command.optString("language", "en")
        val threads = command.optInt("threads", Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
        val beams = command.optInt("beams", 1)
        val rate = 16_000
        AudioReader(File(command.getString("path")), rate, 1, floats = true).use { reader ->
            if (units.first().first > 0) reader.seek(units.first().first)
            var buffer = FloatArray(0)
            var buffered = 0
            var bufferStartMs = units.first().first
            for ((index, unit) in units.withIndex()) {
                val (start, end) = unit
                // Drop anything before this unit (only when units aren't back to back), then read up to its end.
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
                val reporter = thread(isDaemon = true) {
                    try { while (true) { Thread.sleep(250); send(JSONObject().put("type", "progress").put("unit", index).put("value", WhisperNative.progress())) } }
                    catch (_: InterruptedException) {}
                }
                val json = try { JSONObject(WhisperNative.transcribe(model, audio, language, threads, beams)) } finally { reporter.interrupt() }
                val result = TranscriptStore.chunkResult(json.getJSONArray("segments").toString(), end - start)
                send(JSONObject().put("type", "unit").put("index", index).put("startMs", start).put("endMs", end)
                    .put("result", result).put("language", json.opt("language")).put("ms", (System.nanoTime() - began) / 1_000_000))
                buffer.copyInto(buffer, 0, take, buffered); buffered -= take; bufferStartMs = end
            }
        }
        send(JSONObject().put("type", "fileDone"))
    }

    fun parseDevices(json: String): List<Device> = JSONArray(json).let { a ->
        List(a.length()) { a.getJSONObject(it).let { d -> Device(d.getString("name"), d.optString("description"), d.optString("backend"), d.getString("type"), d.optLong("memoryTotal")) } }
    }
}
