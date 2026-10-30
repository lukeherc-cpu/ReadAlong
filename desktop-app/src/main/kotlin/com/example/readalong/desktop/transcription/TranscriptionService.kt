package com.example.readalong.desktop.transcription

import com.example.readalong.desktop.core.AppPaths
import com.example.readalong.desktop.core.LibraryBook
import com.example.readalong.desktop.core.LibraryStore
import com.example.readalong.desktop.core.SettingsStore
import com.example.readalong.desktop.core.companionFile
import com.example.readalong.desktop.core.writeAtomically
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
 * Makes ReadAlongs one book at a time in the transcriber process. Uses the GPU when there is one
 * (and falls back to the CPU if the GPU fails), resumes where it stopped, and saves each finished
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
    private var process: TranscriberProcess? = null
    /** Set when the GPU failed this session; later books go straight to the CPU. */
    private var gpuFailed = false

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
    fun shutdown() { stop(); process?.close(); process = null }

    private fun restart() { stop(); wake() }
    private fun stop() { runner?.cancel(); runner = null; mutableJob.value = null; process?.kill(); process = null }
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
        process?.close(); process = null
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
        var started = System.nanoTime(); var audioDone = 0L
        while (done < plan.size) {
            val worker = process ?: TranscriberProcess.start().also { process = it }
            mutableDevices.value = worker.devices
            mutableJob.update { it?.copy(phase = "Loading ${model.name}", done = done, downloadProgress = null) }
            val preference = if (gpuFailed) "cpu" else settings.value.device
            try {
                val (device, onGpu) = worker.load(modelFile, model.heads, preference)
                mutableJob.update { it?.copy(phase = "Transcribing", device = device, onGpu = onGpu) }
                val cores = Runtime.getRuntime().availableProcessors()
                val threads = if (onGpu) minOf(4, cores) else (cores - 1).coerceIn(1, 12)
                // Beam search on a GPU (accuracy is cheap there); greedy decoding on a CPU, several times faster.
                val beams = if (onGpu) 5 else 1
                while (done < plan.size) {
                    val file = plan[done].file
                    val units = plan.drop(done).takeWhile { it.file == file }
                    val first = done
                    worker.transcribe(File(book.parts[file].path), units.map { it.startMs to it.endMs }, settings.value.language, threads, beams,
                        onProgress = { _, percent -> mutableJob.update { it?.copy(unitPercent = percent.coerceIn(0, 100)) } }) { index, result, _ ->
                        val unit = units[index]
                        transcripts.append(book.id, Chunk(unit.file, unit.startMs, unit.endMs, result), plan.size)
                        done = first + index + 1
                        audioDone += unit.endMs - unit.startMs
                        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                        val speed = if (elapsedMs > 0) audioDone / elapsedMs else null
                        val remaining = plan.drop(done).sumOf { it.endMs - it.startMs }
                        mutableJob.update { it?.copy(done = done, unitPercent = 0, speed = speed, etaMs = speed?.takeIf { s -> s > 0 }?.let { s -> (remaining / s).toLong() }) }
                    }
                }
            } catch (failure: TranscriberException) {
                process?.kill(); process = null
                if (preference != "cpu" && failure.processDied) {
                    // A driver fault or running out of graphics memory: carry on, on the CPU.
                    gpuFailed = true
                    mutableMessages.tryEmit("The GPU stopped responding, so ReadAlong switched to the CPU. It's slower, but your progress is kept.")
                    started = System.nanoTime(); audioDone = 0
                    continue
                }
                throw failure
            }
        }
        if (settings.value.savePackages) savePackage(book)
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
}
