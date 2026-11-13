package com.example.readalong.desktop.transcription

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [processDied]: the transcriber process ended (a native abort such as exit 134, a driver fault, out of memory).
 * [recognition]: whisper itself failed on the audio it was given; the process is still running.
 */
class TranscriberException(message: String, val processDied: Boolean = false, val recognition: Boolean = false) : Exception(message)

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
    private lateinit var logReader: Thread

    /** After a crash: waits briefly for the last stderr lines (the reason it stopped) to be read before they're logged. */
    fun drainLog(): List<String> { runCatching { logReader.join(2_000) }; return recentLog() }

    init {
        Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (line.startsWith(Worker.PREFIX)) runCatching { JSONObject(line.removePrefix(Worker.PREFIX)) }.getOrNull()?.let { messages.trySend(it) }
                }
            } finally { messages.close() }
        }, "transcriber-reader").apply { isDaemon = true }.start()
        logReader = Thread({
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(recentLog) { recentLog.addLast(line); while (recentLog.size > 80) recentLog.removeFirst() }
                if (System.getenv("READALONG_VERBOSE") != null) System.err.println("[transcriber] $line")
            }
        },
            "transcriber-log").apply { isDaemon = true; start() }
    }

    private fun send(command: JSONObject) {
        try { process.outputStream.write((command.toString() + "\n").toByteArray()); process.outputStream.flush() }
        catch (failure: java.io.IOException) { throw TranscriberException("The transcriber stopped.", processDied = true) }
    }

    suspend fun next(): JSONObject {
        val message = messages.receiveCatching().getOrNull()
            ?: throw TranscriberException("The transcriber stopped unexpectedly (exit ${runCatching { process.waitFor(2, TimeUnit.SECONDS); process.exitValue() }.getOrDefault(-1)}).", processDied = true)
        if (message.optString("type") == "error") throw TranscriberException(message.optString("message", "Transcription failed."), recognition = message.optBoolean("recognition"))
        return message
    }

    /**
     * [next], but a transcriber that says nothing for [ms] is taken for hung (a graphics driver that never returns):
     * it is stopped and reported like one that died, so the unit is retried instead of the book waiting forever.
     */
    private suspend fun nextWithin(ms: Long, doing: String): JSONObject = withTimeoutOrNull(ms) { next() } ?: run {
        kill()
        throw TranscriberException("The transcriber stopped responding while $doing.", processDied = true)
    }

    private suspend fun handshake() {
        val ready = nextWithin(START_MS, "starting")
        check(ready.getString("type") == "ready")
        devices = Worker.parseDevices(ready.getJSONArray("devices").toString())
        systemInfo = ready.optString("system")
    }

    /** Loads [model]; returns the device it runs on ("CPU" or the GPU's name) and whether that's a GPU. */
    suspend fun load(model: File, heads: Int, device: String): Pair<String, Boolean> {
        send(JSONObject().put("cmd", "load").put("model", model.absolutePath).put("heads", heads).put("device", device))
        val loaded = nextWithin(LOAD_MS, "loading the speech model")
        return loaded.getString("device") to loaded.getBoolean("gpu")
    }

    /** Transcribes [units] of [file]; [onUnit] gets each unit's chunk result as it finishes. */
    suspend fun transcribe(file: File, units: List<Pair<Long, Long>>, language: String, threads: Int, beams: Int,
        stallMs: Long = STALL_MS, onProgress: (unit: Int, percent: Int) -> Unit, onUnit: suspend (index: Int, result: JSONObject, ms: Long) -> Unit) {
        send(JSONObject().put("cmd", "file").put("path", file.absolutePath).put("language", language).put("threads", threads).put("beams", beams)
            .put("units", JSONArray(units.map { JSONArray(listOf(it.first, it.second)) })))
        // The transcriber reports progress four times a second even when whisper is stuck, so silence alone doesn't
        // show a hang: what counts is progress that moves, or a unit that finishes, within [stallMs].
        var advanced = System.nanoTime()
        var lastValue = -1
        while (true) {
            val message = nextWithin(stallMs, "recognizing speech")
            when (message.getString("type")) {
                "progress" -> {
                    val value = message.getInt("value")
                    if (value != lastValue) { lastValue = value; advanced = System.nanoTime() }
                    else if ((System.nanoTime() - advanced) / 1_000_000 > stallMs) {
                        kill()
                        throw TranscriberException("The transcriber made no progress for ${stallMs / 60_000} minutes.", processDied = true)
                    }
                    onProgress(message.getInt("unit"), value)
                }
                "unit" -> { advanced = System.nanoTime(); lastValue = -1; onUnit(message.getInt("index"), message.getJSONObject("result"), message.optLong("ms")) }
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
        /** A transcriber that hasn't said it is ready by now won't. */
        const val START_MS = 2 * 60_000L
        /** Loading the largest model onto a slow graphics card (compiling its shaders the first time) takes a while. */
        const val LOAD_MS = 10 * 60_000L
        /** One unit is at most 30 s of audio; the slowest supported computer needs a few minutes for it with the largest model. */
        val STALL_MS: Long = System.getProperty("readalong.stallMs")?.toLongOrNull() ?: 10 * 60_000L

        /** Starts ReadAlong again with --transcriber: the packaged launcher, or java with this classpath when run from Gradle. */
        suspend fun start(): TranscriberProcess = withContext(Dispatchers.IO) {
            val command = ProcessHandle.current().info().command().orElse("java")
            val name = File(command).name.lowercase()
            val args = if (name == "java" || name == "java.exe" || name == "javaw.exe") {
                val properties = listOf("compose.application.resources.dir", "readalong.native", "readalong.home", "java.library.path")
                    .mapNotNull { key -> System.getProperty(key)?.let { "-D$key=$it" } }
                listOf(command, "-Xmx1g") + properties + listOf("-cp", System.getProperty("java.class.path"), "com.example.readalong.desktop.MainKt", "--transcriber")
            } else listOf(command, "--transcriber")
            val process = ProcessBuilder(args).apply {
                // The Linux launcher re-runs itself and marks the second run with an environment variable; a copy
                // started with that mark inherited would read --transcriber as a Java option and stop.
                environment().keys.removeIf { it.startsWith("_JPACKAGE") }
            }.start()
            TranscriberProcess(process).also {
                try { it.handshake() } catch (failure: Exception) { it.kill(); throw failure }
            }
        }
    }
}
