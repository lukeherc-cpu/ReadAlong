package com.example.readalong.desktop.transcription

import com.example.readalong.desktop.core.AppPaths
import com.example.readalong.desktop.core.LibraryBook
import com.example.readalong.desktop.core.LibraryStore
import com.example.readalong.desktop.core.SettingsStore
import com.example.readalong.desktop.core.companionFile
import com.example.readalong.desktop.core.writeAtomically
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File

/** One transcription unit: [startMs] to [endMs] of the book's audio file [file], file-relative. */
data class PlanUnit(val file: Int, val startMs: Long, val endMs: Long)

/** The phone's plan (TranscriptionPlan): units of at most 30 s per file, never crossing a chapter start. */
fun transcriptionPlan(book: LibraryBook): List<PlanUnit> {
    val units = mutableListOf<PlanUnit>()
    book.parts.forEachIndexed { index, part ->
        val starts = book.chapters.map { it.startMs - part.offsetMs }.filter { it > 0 && it < part.durationMs }.sorted()
        var position = 0L
        while (position < part.durationMs) {
            val nextChapter = starts.firstOrNull { it > position } ?: part.durationMs
            val end = minOf(position + TranscriptStore.CHUNK_MS, part.durationMs, nextChapter)
            units += PlanUnit(index, position, end)
            position = end
        }
    }
    return units
}

data class JobState(
    val bookId: String,
    val phase: String,
    val done: Int = 0,
    val total: Int = 0,
    val unitPercent: Int = 0,
    val device: String? = null,
    val onGpu: Boolean = false,
    /** Seconds of audio per second of work. */
    val speed: Double? = null,
    val etaMs: Long? = null,
    val downloadProgress: Float? = null,
) {
    val fraction: Float get() = if (total == 0) 0f else ((done + unitPercent / 100f) / total).coerceIn(0f, 1f)
}

/**
 * Makes ReadAlongs one book at a time, in one transcriber process or (aggressive) several working on the book together.
 * Uses the GPU when there is one (and falls back to the CPU if the GPU fails), resumes where it stopped, and saves each finished
 * book as a .readalong package beside its audio for the phone.
 */
class TranscriptionService(private val scope: CoroutineScope, private val library: LibraryStore, private val settings: SettingsStore,
    val transcripts: TranscriptStore, val models: ModelStore, probe: Boolean = true) {
    private val queueFile = File(AppPaths.data, "queue.json")
    private val mutableQueue = MutableStateFlow(loadQueue())
    val queue: StateFlow<List<String>> = mutableQueue
    private val mutableJob = MutableStateFlow<JobState?>(null)
    val job: StateFlow<JobState?> = mutableJob
    private val mutablePaused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = mutablePaused
    private val mutableDevices = MutableStateFlow<List<Device>?>(null)
    /** Null until the transcriber has reported this computer's devices. */
    val devices: StateFlow<List<Device>?> = mutableDevices
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = mutableMessages
    private val mutableErrors = MutableStateFlow(emptyMap<String, String>())
    val errors: StateFlow<Map<String, String>> = mutableErrors
    private var runner: Job? = null
    /** The transcriber processes working now (one per lane). */
    private val processes = java.util.concurrent.CopyOnWriteArrayList<TranscriberProcess>()

    init {
        if (probe) {
            scope.launch(Dispatchers.IO) { probeDevices() }
            if (mutableQueue.value.isNotEmpty()) wake()
        } else mutableDevices.value = emptyList()
    }

    fun model(): SpeechModel = SpeechModels.find(settings.value.modelId)
        ?: SpeechModels.default(settings.value.language)

    private suspend fun probeDevices() {
        val cache = File(AppPaths.data, "devices.json")
        runCatching { mutableDevices.value = Worker.parseDevices(cache.readText()) }
        try {
            TranscriberProcess.start().use { p ->
                mutableDevices.value = p.devices
                cache.writeAtomically(JSONArray(p.devices.map { org.json.JSONObject().put("name", it.name).put("description", it.description)
                    .put("backend", it.backend).put("type", it.type).put("memoryTotal", it.memoryTotal) }).toString())
            }
        } catch (failure: Exception) {
            if (mutableDevices.value == null) mutableDevices.value = emptyList()
            System.err.println("ReadAlong: couldn't list devices: ${failure.message}")
        }
    }

    fun plan(book: LibraryBook): List<PlanUnit> = transcriptionPlan(book)

    /** Adds [bookId] to the queue (first, if [now]) and starts working. */
    fun enqueue(bookId: String, now: Boolean = false) {
        mutableErrors.update { it - bookId }
        mutableQueue.update { queue -> if (now) listOf(bookId) + (queue - bookId) else if (bookId in queue) queue else queue + bookId }
        saveQueue()
        if (now && mutableJob.value?.bookId != bookId) restart() else wake()
    }
    fun remove(bookId: String) {
        mutableQueue.update { it - bookId }; saveQueue()
        if (mutableJob.value?.bookId == bookId) restart()
    }
    fun move(bookId: String, offset: Int) {
        mutableQueue.update { queue ->
            val from = queue.indexOf(bookId); if (from < 0) return@update queue
            val to = (from + offset).coerceIn(0, queue.lastIndex)
            queue.toMutableList().apply { removeAt(from); add(to, bookId) }
        }
        saveQueue()
        if (mutableQueue.value.firstOrNull() != mutableJob.value?.bookId) restart()
    }
    fun pause(paused: Boolean) { mutablePaused.value = paused; if (paused) stop() else wake() }
    /** Removes a book's ReadAlong (and stops making it). */
    fun discard(bookId: String) { remove(bookId); transcripts.remove(bookId) }
    fun shutdown() { stop(); processes.forEach { it.close() }; processes.clear() }

    private fun restart() { stop(); wake() }
    private fun stop() { runner?.cancel(); runner = null; mutableJob.value = null; processes.forEach { it.kill() }; processes.clear() }
    private fun wake() {
        if (runner?.isActive == true || mutablePaused.value) return
        runner = scope.launch(Dispatchers.IO) { run() }
    }

    private suspend fun run() {
        while (currentCoroutineContext().isActive) {
            val bookId = mutableQueue.value.firstOrNull() ?: break
            val book = library.book(bookId)
            if (book == null) { mutableQueue.update { it - bookId }; continue }
            try {
                transcribe(book)
                mutableQueue.update { it - bookId }; saveQueue()
                mutableMessages.tryEmit("Your ReadAlong of ${book.title} is ready.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableErrors.update { it + (bookId to (failure.message ?: "Transcription stopped.")) }
                mutableQueue.update { it - bookId }; saveQueue()
                mutableMessages.tryEmit("ReadAlong stopped for ${book.title}: ${failure.message}")
            } finally { mutableJob.value = null }
        }
    }

    private suspend fun transcribe(book: LibraryBook) {
        val plan = plan(book)
        require(plan.isNotEmpty()) { "This book has no audio to transcribe." }
        val model = model()
        mutableJob.value = JobState(book.id, "Starting", total = plan.size)
        val info = transcripts.info(book.id)
        val existing = transcripts.chunks(book.id)
        // Resume only the same model's work on the same plan.
        val resumable = info != null && !info.imported && info.model == model.packageId && existing.size <= plan.size &&
            existing.withIndex().all { (i, c) -> plan[i].let { it.file == c.file && it.startMs == c.startMs && it.endMs == c.endMs } }
        if (info?.complete == true && (info.imported || resumable)) return
        if (!resumable) transcripts.begin(book.id, model.packageId, model.engineName, plan.size)
        var done = if (resumable) existing.size else 0

        val modelFile = if (models.isInstalled(model)) models.file(model) else {
            mutableJob.update { it?.copy(phase = "Downloading ${model.name}", downloadProgress = 0f) }
            val watcher = scope.launch { models.downloads.collect { d -> d[model.id]?.let { p -> mutableJob.update { it?.copy(downloadProgress = p) } } } }
            try { models.download(model) } finally { watcher.cancel() }
        }
        // One or more transcribers ("lanes") work through the book together, each taking the next few units of one file.
        // Results are saved in book order, so a stop keeps every unit up to the first one still missing and resuming works as before.
        val lanes = lanes(model)
        val aggressive = settings.value.aggressive
        val lock = kotlinx.coroutines.sync.Mutex()
        val pending = ArrayDeque<List<Int>>()
        var next = done
        while (next < plan.size) {
            val file = plan[next].file
            val batch = (next until plan.size).asSequence().takeWhile { plan[it].file == file }.take(if (lanes.size > 1) BATCH_UNITS else Int.MAX_VALUE).toList()
            pending.addLast(batch); next += batch.size
        }
        val waiting = HashMap<Int, org.json.JSONObject>()
        val unitPercent = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        val laneDevices = arrayOfNulls<Pair<String, Boolean>>(lanes.size)
        val started = System.nanoTime(); var audioDone = 0L
        val audioLeft = plan.drop(done).sumOf { it.endMs - it.startMs }
        var toldFallback = false

        suspend fun lane(number: Int, wanted: String, threads: Int) {
            // A crash on the GPU is often a one-off (a driver hiccup, a timeout on one hard chunk), so the GPU gets
            // [GPU_RETRIES] fresh starts before this lane moves to the CPU. The next book tries the GPU again.
            var preference = wanted
            var gpuCrashes = 0
            var worker: TranscriberProcess? = null
            try {
                while (true) {
                    val batch = lock.withLock { pending.removeFirstOrNull() } ?: return
                    var finished = 0
                    try {
                        val current = worker ?: TranscriberProcess.start().also { fresh ->
                            worker = fresh; processes += fresh
                            mutableDevices.value = fresh.devices
                            val loaded = fresh.load(modelFile, model.heads, preference)
                            lock.withLock {
                                laneDevices[number] = loaded
                                val running = laneDevices.filterNotNull()
                                mutableJob.update { it?.copy(phase = "Transcribing", device = running.map { d -> d.first }.distinct().joinToString(" + "), onGpu = running.any { d -> d.second }) }
                            }
                        }
                        val onGpu = laneDevices[number]?.second == true
                        // Beam search on a GPU (accuracy is cheap there) unless aggressive; greedy decoding on a CPU, several times faster.
                        // After a GPU crash, greedy there too: a driver that resets long-running work (amdgpu's job
                        // timeout on Linux, Windows' TDR) gets much shorter submissions.
                        val beams = if (onGpu && gpuCrashes == 0 && !aggressive) 5 else 1
                        val units = batch.map { plan[it] }
                                                // A GPU lane that moved to the CPU needs the processor threads a CPU run would get.
                        val lanesThreads = if (preference == wanted) threads else (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)
                        current.transcribe(File(book.parts[units.first().file].path), units.map { it.startMs to it.endMs }, settings.value.language, lanesThreads, beams,
                            onProgress = { _, percent ->
                                unitPercent[number] = percent.coerceIn(0, 100)
                                mutableJob.update { it?.copy(unitPercent = unitPercent.values.sum()) }
                            }) { index, result, _ ->
                            finished = index + 1
                            lock.withLock {
                                val unit = plan[batch[index]]
                                waiting[batch[index]] = result
                                audioDone += unit.endMs - unit.startMs
                                while (true) {
                                    val ready = waiting.remove(done) ?: break
                                    val saved = plan[done]
                                    transcripts.append(book.id, Chunk(saved.file, saved.startMs, saved.endMs, ready), plan.size)
                                    done++
                                }
                                unitPercent.remove(number)
                                val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                                val speed = if (elapsedMs > 0) audioDone / elapsedMs else null
                                mutableJob.update { it?.copy(done = done + waiting.size, unitPercent = unitPercent.values.sum(), speed = speed,
                                    etaMs = speed?.takeIf { s -> s > 0 }?.let { s -> ((audioLeft - audioDone).coerceAtLeast(0) / s).toLong() }) }
                            }
                        }
                    } catch (failure: TranscriberException) {
                        // Hand back what this lane hadn't finished, at the front so it's saved in order.
                        lock.withLock { if (finished < batch.size) pending.addFirst(batch.drop(finished)) }
                        unitPercent.remove(number)
                        worker?.let { w -> if (failure.processDied) saveCrashLog(w, book, model, preference); w.kill(); processes -= w }
                        worker = null
                        if (preference != "cpu" && failure.processDied) {
                            // A driver fault, a GPU timeout or running out of graphics memory: start again where it stopped.
                            gpuCrashes++
                            if (gpuCrashes > GPU_RETRIES) {
                                preference = "cpu"
                                if (!toldFallback) { toldFallback = true; mutableMessages.tryEmit("The GPU stopped responding again, so ReadAlong is finishing ${book.title} on the CPU. It's slower, but your progress is kept.") }
                            }
                            continue
                        }
                        throw failure
                    }
                }
            } finally { worker?.let { w -> w.close(); processes -= w } }
        }
        mutableJob.update { it?.copy(phase = "Loading ${model.name}", done = done, downloadProgress = null) }
        coroutineScope { lanes.forEachIndexed { number, (preference, threads) -> launch { lane(number, preference, threads) } } }
        check(done == plan.size) { "Transcription stopped before the end of the book." }
        if (settings.value.savePackages) savePackage(book)
    }

    /**
     * Which transcribers work on a book together, as (device preference, threads). Normally one. Aggressive runs two
     * on the graphics card (one computes while the other decodes text on the processor, so the card stays busy) plus one
     * on the cores left over, or, without a usable card, several of about six threads each, since Whisper gains little
     * from more threads in one run.
     */
    private suspend fun lanes(model: SpeechModel): List<Pair<String, Int>> {
        val s = settings.value
        val cores = Runtime.getRuntime().availableProcessors()
        val devices = mutableDevices.first { it != null }.orEmpty()
        val gpu = devices.filter { it.isGpu }.maxByOrNull { it.memoryTotal }?.takeIf { s.device != "cpu" }
        // The self-test's way to run several lanes on a small CI machine.
        System.getProperty("readalong.lanes")?.toIntOrNull()?.let { count -> return List(count) { s.device to 2 } }
        if (!s.aggressive) return listOf(s.device to if (gpu != null) minOf(4, cores) else (cores - 1).coerceIn(1, 12))
        if (gpu != null) {
            // Each run on the card holds its own copy of the model; a second one only where it fits with room to spare.
            val gpuLanes = if (gpu.memoryTotal <= 0 || gpu.memoryTotal > model.sizeBytes * 4) 2 else 1
            val spare = cores - 2 * gpuLanes - 1
            return List(gpuLanes) { s.device to 2 } + if (spare >= 4) listOf("cpu" to spare.coerceAtMost(8)) else emptyList()
        }
        val count = (cores / 6).coerceIn(1, 4)
        return List(count) { "cpu" to (cores / count).coerceIn(1, 8) }
    }

    /** The transcriber's last log lines when it died, for working out why (the GPU driver, memory, a bad file). */
    private fun saveCrashLog(worker: TranscriberProcess, book: LibraryBook, model: SpeechModel, preference: String) = runCatching {
        val log = File(AppPaths.data, "transcriber-crash.log")
        val entry = buildString {
            appendLine("== ${java.time.LocalDateTime.now()} · ${book.title} · ${model.id} · device $preference · ${AppPaths.platformName}")
            worker.devices.forEach { appendLine("device: ${it.type} ${it.label} ${it.memoryTotal / (1 shl 20)} MB") }
            worker.drainLog().forEach { appendLine(it) }
        }
        // Keep the latest few crashes only.
        val previous = if (log.isFile) log.readText().takeLast(60_000) else ""
        log.writeAtomically(previous + entry)
    }

    /** Where the phone's library scan finds a book's package: <folder>/<folder>.readalong, or <file>.readalong for a single file. */
    fun packageFile(book: LibraryBook): File = book.companionFile("readalong")

    fun savePackage(book: LibraryBook, destination: File = packageFile(book)): Boolean = try {
        transcripts.exportPackage(book, destination); true
    } catch (failure: Exception) {
        mutableMessages.tryEmit("Couldn't save ${destination.name} beside the audiobook: ${failure.message}"); false
    }

    private fun loadQueue(): List<String> = runCatching { JSONArray(queueFile.readText()).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
    private fun saveQueue() = runCatching { queueFile.writeAtomically(JSONArray(mutableQueue.value).toString()) }

    private companion object {
        /** Fresh starts on the GPU after a crash before a book moves to the CPU. */
        const val GPU_RETRIES = 2
        /** Units a lane takes at a time when several work on one book: about two minutes of audio. */
        const val BATCH_UNITS = 4
    }
}
