package com.example.readalong.desktop.transcription

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class TranscriberException(message: String, val processDied: Boolean = false) : Exception(message)

/** The app's side of [Worker]: starts the transcriber process and exchanges JSON lines with it. */
class TranscriberProcess private constructor(private val process: Process) : AutoCloseable {
    private val messages = Channel<JSONObject>(Channel.UNLIMITED)
    lateinit var devices: List<Device>
        private set
    var systemInfo = ""
        private set

    private val recentLog = ArrayDeque<String>()
    /** The transcriber's last lines of stderr (whisper.cpp and ggml log there), for diagnosing a failed load. */
    fun recentLog(): List<String> = synchronized(recentLog) { recentLog.toList() }

    init {
        Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (line.startsWith(Worker.PREFIX)) runCatching { JSONObject(line.removePrefix(Worker.PREFIX)) }.getOrNull()?.let { messages.trySend(it) }
                }
            } finally { messages.close() }
        }, "transcriber-reader").apply { isDaemon = true }.start()
        Thread({
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(recentLog) { recentLog.addLast(line); while (recentLog.size > 80) recentLog.removeFirst() }
                if (System.getenv("READALONG_VERBOSE") != null) System.err.println("[transcriber] $line")
            }
        },
            "transcriber-log").apply { isDaemon = true }.start()
    }

    private fun send(command: JSONObject) {
        try { process.outputStream.write((command.toString() + "\n").toByteArray()); process.outputStream.flush() }
        catch (failure: java.io.IOException) { throw TranscriberException("The transcriber stopped.", processDied = true) }
    }

    suspend fun next(): JSONObject {
        val message = messages.receiveCatching().getOrNull()
            ?: throw TranscriberException("The transcriber stopped unexpectedly (exit ${runCatching { process.waitFor(2, TimeUnit.SECONDS); process.exitValue() }.getOrDefault(-1)}).", processDied = true)
        if (message.optString("type") == "error") throw TranscriberException(message.optString("message", "Transcription failed."))
        return message
    }

    private suspend fun handshake() {
        val ready = next()
        check(ready.getString("type") == "ready")
        devices = Worker.parseDevices(ready.getJSONArray("devices").toString())
        systemInfo = ready.optString("system")
    }

    /** Loads [model]; returns the device it runs on ("CPU" or the GPU's name) and whether that's a GPU. */
    suspend fun load(model: File, heads: Int, device: String): Pair<String, Boolean> {
        send(JSONObject().put("cmd", "load").put("model", model.absolutePath).put("heads", heads).put("device", device))
        val loaded = next()
        return loaded.getString("device") to loaded.getBoolean("gpu")
    }

    /** Transcribes [units] of [file]; [onUnit] gets each unit's chunk result as it finishes. */
    suspend fun transcribe(file: File, units: List<Pair<Long, Long>>, language: String, threads: Int, beams: Int,
        onProgress: (unit: Int, percent: Int) -> Unit, onUnit: suspend (index: Int, result: JSONObject, ms: Long) -> Unit) {
        send(JSONObject().put("cmd", "file").put("path", file.absolutePath).put("language", language).put("threads", threads).put("beams", beams)
            .put("units", JSONArray(units.map { JSONArray(listOf(it.first, it.second)) })))
        while (true) {
            val message = next()
            when (message.getString("type")) {
                "progress" -> onProgress(message.getInt("unit"), message.getInt("value"))
                "unit" -> onUnit(message.getInt("index"), message.getJSONObject("result"), message.optLong("ms"))
                "fileDone" -> return
            }
        }
    }

    override fun close() {
        runCatching { send(JSONObject().put("cmd", "quit")) }
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
    }
    fun kill() { process.destroyForcibly() }

    companion object {
        /** Starts ReadAlong again with --transcriber: the packaged launcher, or java with this classpath when run from Gradle. */
        suspend fun start(): TranscriberProcess = withContext(Dispatchers.IO) {
            val command = ProcessHandle.current().info().command().orElse("java")
            val name = File(command).name.lowercase()
            val args = if (name == "java" || name == "java.exe" || name == "javaw.exe") {
                val properties = listOf("compose.application.resources.dir", "readalong.native", "readalong.home", "java.library.path")
                    .mapNotNull { key -> System.getProperty(key)?.let { "-D$key=$it" } }
                listOf(command, "-Xmx1g") + properties + listOf("-cp", System.getProperty("java.class.path"), "com.example.readalong.desktop.MainKt", "--transcriber")
            } else listOf(command, "--transcriber")
            val process = ProcessBuilder(args).start()
            TranscriberProcess(process).also {
                try { it.handshake() } catch (failure: Exception) { it.kill(); throw failure }
            }
        }
    }
}
