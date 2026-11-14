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
    /** Why each stopped book stopped, kept beside queue.json so a restart still shows it under Stopped. */
    private val errorsFile = File(AppPaths.data, "errors.json")
    private val mutableErrors = MutableStateFlow(runCatching {
        org.json.JSONObject(errorsFile.readText()).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    }.getOrDefault(emptyMap()))
    val errors: StateFlow<Map<String, String>> = mutableErrors
    private val finishedFile = File(AppPaths.data, "queue-finished.json")
    private val mutableFinished = MutableStateFlow(loadList(finishedFile))
    /** Books whose ReadAlong finished here, newest first, shown under "Transcribed" in the queue until cleared (as on the phone). */
    val finished: StateFlow<List<String>> = mutableFinished
    private var runner: Job? = null
    /** The transcriber processes working now (one per lane). */
    private val processes = java.util.concurrent.CopyOnWriteArrayList<TranscriberProcess>()

    init {
        if (probe) {
            scope.launch(Dispatchers.IO) { probeDevices() }
            if (mutableQueue.value.isNotEmpty()) wake()
        } else mutableDevices.value = emptyList()
    }

    fun model(): SpeechModel = SpeechModels.find(settings.value.modelId) ?: defaultModel()

    /** The model used until one is chosen in Settings: by this computer's graphics card (see [SpeechModels.default]). */
    fun defaultModel(): SpeechModel = SpeechModels.default(settings.value.language, mutableDevices.value)

    /**
     * A finished ReadAlong of [bookId] arrived from the phone, made with [packageModel]. True when this computer should
     * carry on with its own instead: the book is being made or queued here, with a better model than the phone's.
     */
    fun prefersOwn(bookId: String, packageModel: String?): Boolean =
        (bookId in mutableQueue.value || mutableJob.value?.bookId == bookId) && SpeechModels.quality(model().packageId) > SpeechModels.quality(packageModel)

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
        updateErrors { it - bookId }
        if (bookId in mutableFinished.value) { mutableFinished.update { it - bookId }; saveFinished() }
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
    /** The queue's play button: moves [bookId] to the front and transcribes it now, resuming the queue if it was paused. */
    fun start(bookId: String) {
        mutablePaused.value = false
        enqueue(bookId, now = true)
        wake()
    }
    /** Saves a new order (from dragging in the queue). Books missing from [ids] keep their place at the end. */
    fun reorder(ids: List<String>) {
        mutableQueue.update { queue -> ids.filter { it in queue }.distinct().let { ordered -> ordered + (queue - ordered.toSet()) } }
        saveQueue()
        if (mutableJob.value != null && mutableQueue.value.firstOrNull() != mutableJob.value?.bookId) restart()
    }
    /** Takes a stopped book off the queue's "Stopped" list; whatever it made so far stays. */
    fun dismissError(bookId: String) = updateErrors { it - bookId }
    /** A finished ReadAlong arrived from the phone: the book leaves the queue and shows under "Transcribed", as if made here. */
    fun imported(bookId: String) {
        if (mutableJob.value?.bookId == bookId) restart()
        mutableQueue.update { it - bookId }; saveQueue()
        updateErrors { it - bookId }
        mutableFinished.update { listOf(bookId) + (it - bookId) }; saveFinished()
    }
    /** Takes a finished book off the queue's "Transcribed" list; its ReadAlong stays. */
    fun clearFinished(bookId: String) { mutableFinished.update { it - bookId }; saveFinished() }
    /**
     * Where each book's ReadAlong stands, for a linked phone: being made (with its progress), queued, finished here, or
     * stopped. A paused queue still counts as queued: the book is this computer's to make.
     */
    fun statuses(): Map<String, com.example.readalong.domain.link.ComputerJob> {
        val job = mutableJob.value
        val model = model().packageId
        val all = LinkedHashMap<String, com.example.readalong.domain.link.ComputerJob>()
        mutableFinished.value.forEach { all[it] = com.example.readalong.domain.link.ComputerJob("", "", com.example.readalong.domain.link.ComputerJob.DONE) }
        mutableErrors.value.forEach { (id, why) -> all[id] = com.example.readalong.domain.link.ComputerJob("", "", com.example.readalong.domain.link.ComputerJob.STOPPED, error = why.take(300)) }
        mutableQueue.value.forEach { id ->
            all[id] = if (job?.bookId == id) com.example.readalong.domain.link.ComputerJob("", "", com.example.readalong.domain.link.ComputerJob.WORKING, (job.fraction * 1000).toInt(), 1000, model)
                else com.example.readalong.domain.link.ComputerJob("", "", com.example.readalong.domain.link.ComputerJob.QUEUED, model = model)
        }
        return all
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
        // The computer stays awake while there is a book to work on, and may sleep again once the queue is empty or paused.
        com.example.readalong.desktop.core.KeepAwake.acquire()
        try { work() } finally { com.example.readalong.desktop.core.KeepAwake.release() }
    }

    private suspend fun work() {
        while (currentCoroutineContext().isActive) {
            val bookId = mutableQueue.value.firstOrNull() ?: break
            val book = library.book(bookId)
            if (book == null) { mutableQueue.update { it - bookId }; continue }
            try {
                transcribe(book)
                mutableQueue.update { it - bookId }; saveQueue()
                mutableFinished.update { listOf(bookId) + (it - bookId) }; saveFinished()
                mutableMessages.tryEmit("Your ReadAlong of ${book.title} is ready.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                updateErrors { it + (bookId to (failure.message ?: "Transcription stopped.")) }
                mutableQueue.update { it - bookId }; saveQueue()
                mutableMessages.tryEmit("ReadAlong stopped for ${book.title}: ${failure.message}")
            } finally { mutableJob.value = null }
        }
    }

    private suspend fun transcribe(book: LibraryBook) {
        val plan = plan(book)
        require(plan.isNotEmpty()) { "This book has no audio to transcribe." }
        val info = transcripts.info(book.id)
        // A book begun before the default model changed (a new graphics card, an update) finishes with the model it started on.
        val model = SpeechModels.byPackage(info?.model)?.takeIf { settings.value.modelId == null && info?.complete == false && !info.imported } ?: model()
        mutableJob.value = JobState(book.id, "Starting", total = plan.size)
        val existing = transcripts.chunks(book.id)
        // Resume only the same model's work on the same plan.
        val resumable = info != null && !info.imported && info.model == model.packageId && existing.size <= plan.size &&
            existing.withIndex().all { (i, c) -> plan[i].let { it.file == c.file && it.startMs == c.startMs && it.endMs == c.endMs } }
        // A finished ReadAlong is only "done" when it has text to show; one that doesn't is made again.
        if (info?.complete == true && (info.imported || resumable) && transcripts.segments(book).isNotEmpty()) return
        if (info?.complete == true && info.imported) transcripts.remove(book.id)
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
        val crashes = CrashPolicy()
        var toldFallback = false
        val cpuThreads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)

        /** Keeps [result] for plan unit [index] and saves every unit up to the first one still missing, in book order. */
        suspend fun keep(number: Int, index: Int, result: org.json.JSONObject) = lock.withLock {
            val unit = plan[index]
            waiting[index] = result
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

        fun onProgress(number: Int, percent: Int) {
            unitPercent[number] = percent.coerceIn(0, 100)
            mutableJob.update { it?.copy(unitPercent = unitPercent.values.sum()) }
        }

        /** Plan unit [index] alone on the CPU, without DTW word times, in its own transcriber. False if that failed too. */
        suspend fun rescue(number: Int, index: Int): Boolean {
            val unit = plan[index]
            var rescuer: TranscriberProcess? = null
            return try {
                val fresh = TranscriberProcess.start().also { rescuer = it; processes += it }
                fresh.load(modelFile, 0, "cpu")
                fresh.transcribe(File(book.parts[unit.file].path), listOf(unit.startMs to unit.endMs), settings.value.language, cpuThreads, 1,
                    onProgress = { _, percent -> onProgress(number, percent) }) { _, result, _ -> keep(number, index, result) }
                true
            } catch (failure: TranscriberException) {
                currentCoroutineContext().ensureActive() // Paused or removed: the process was stopped on purpose.
                if (!failure.processDied && !failure.recognition) throw failure
                rescuer?.let { saveCrashLog(it, book, model, "cpu, no word alignment", unit, failure) }
                false
            } finally { rescuer?.let { r -> r.close(); processes -= r } }
        }

        /** Last resort for a unit nothing could recognize: it's saved with no words, so the rest of the book carries on. */
        suspend fun skip(number: Int, index: Int) {
            keep(number, index, org.json.JSONObject().put("text", "").put("words", JSONArray()))
            val unit = plan[index]
            mutableMessages.tryEmit("ReadAlong couldn't transcribe ${(unit.endMs - unit.startMs) / 1000} s of ${book.title} at " +
                "${clock(book.parts[unit.file].offsetMs + unit.startMs)} and left it out. The rest of the book carries on.")
        }

        suspend fun lane(number: Int, wanted: String, threads: Int) {
            // A failure is retried on the unit it happened on (see CrashPolicy), so one hard chunk neither moves the
            // book off the GPU nor stops it. The GPU is given up for this book only when many different units fail.
            var preference = wanted
            var worker: TranscriberProcess? = null
            try {
                while (true) {
                    if (preference != "cpu" && crashes.gpuUnstable) {
                        worker?.let { w -> w.close(); processes -= w }
                        worker = null
                        preference = "cpu"
                        if (!toldFallback) { toldFallback = true; mutableMessages.tryEmit("The GPU kept failing, so ReadAlong is finishing ${book.title} on the CPU. It's slower, but your progress is kept.") }
                    }
                    val batch = lock.withLock { pending.removeFirstOrNull() } ?: return
                    var finished = 0
                    var loaded = worker != null
                    try {
                        val current = worker ?: TranscriberProcess.start().also { fresh ->
                            worker = fresh; processes += fresh
                            mutableDevices.value = fresh.devices
                            val device = fresh.load(modelFile, model.heads, preference)
                            loaded = true
                            lock.withLock {
                                laneDevices[number] = device
                                val running = laneDevices.filterNotNull()
                                mutableJob.update { it?.copy(phase = "Transcribing", device = running.map { d -> d.first }.distinct().joinToString(" + "), onGpu = running.any { d -> d.second }) }
                            }
                        }
                        val onGpu = laneDevices[number]?.second == true
                        // Beam search on a GPU (accuracy is cheap there) unless aggressive; greedy decoding on a CPU, several times faster.
                        // Greedy too for a unit being tried again, and once the GPU has failed on a few units.
                        val retrying = batch.size == 1 && crashes.failures(batch.first()) > 0
                        val beams = if (onGpu && !retrying && !crashes.preferGreedy && !aggressive) 5 else 1
                        val units = batch.map { plan[it] }
                        // A GPU lane that moved to the CPU needs the processor threads a CPU run would get.
                        val laneThreads = if (preference == wanted) threads else cpuThreads
                        current.transcribe(File(book.parts[units.first().file].path), units.map { it.startMs to it.endMs }, settings.value.language, laneThreads, beams,
                            onProgress = { _, percent -> onProgress(number, percent) }) { index, result, _ ->
                            finished = index + 1
                            keep(number, batch[index], result)
                        }
                    } catch (failure: TranscriberException) {
                        currentCoroutineContext().ensureActive() // Paused or removed: the process was stopped on purpose.
                        val onGpu = laneDevices[number]?.second == true && preference != "cpu"
                        unitPercent.remove(number)
                        val rest = batch.drop(finished)
                        val unitFailed = (failure.processDied || failure.recognition) && loaded
                        worker?.let { w ->
                            if (failure.processDied || failure.recognition) saveCrashLog(w, book, model, preference, rest.firstOrNull()?.takeIf { unitFailed }?.let { plan[it] }, failure)
                            w.kill(); processes -= w
                        }
                        worker = null
                        // Every unit was already saved (it stopped while finishing the file): nothing to redo.
                        if (rest.isEmpty() && failure.processDied) continue
                        if (!unitFailed) {
                            // Hand back what this lane hadn't finished, at the front so it's saved in order.
                            lock.withLock { pending.addFirst(rest) }
                            // Died while loading the model on the GPU: try again, and after a couple of times on the CPU.
                            if (failure.processDied && preference != "cpu") { crashes.gpuStartFailed(); continue }
                            throw failure
                        }
                        // The unit being recognized failed: it's tried again on its own; the units after it go back in the queue.
                        val index = rest.first()
                        lock.withLock { if (rest.size > 1) pending.addFirst(rest.drop(1)) }
                        when (crashes.failed(index, onGpu)) {
                            CrashPolicy.Action.RETRY -> lock.withLock { pending.addFirst(listOf(index)) }
                            CrashPolicy.Action.RESCUE -> if (!rescue(number, index)) skip(number, index)
                            CrashPolicy.Action.SKIP -> skip(number, index)
                        }
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
    private fun saveCrashLog(worker: TranscriberProcess, book: LibraryBook, model: SpeechModel, preference: String, unit: PlanUnit?,
        failure: TranscriberException) = runCatching {
        val log = File(AppPaths.data, "transcriber-crash.log")
        val entry = buildString {
            appendLine("== ${java.time.LocalDateTime.now()} · ${book.title} · ${model.id} · device $preference · ${AppPaths.platformName}")
            appendLine(failure.message + (unit?.let { " · file ${it.file + 1}, ${clock(it.startMs)}–${clock(it.endMs)}" } ?: ""))
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

    private fun loadQueue(): List<String> = loadList(queueFile)
    private fun loadList(file: File): List<String> = runCatching { JSONArray(file.readText()).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
    private fun saveFinished() = runCatching { finishedFile.writeAtomically(JSONArray(mutableFinished.value).toString()) }
    private fun updateErrors(change: (Map<String, String>) -> Map<String, String>) {
        mutableErrors.update(change)
        runCatching { errorsFile.writeAtomically(org.json.JSONObject(mutableErrors.value).toString()) }
    }
    private fun saveQueue() = runCatching { queueFile.writeAtomically(JSONArray(mutableQueue.value).toString()) }

    private companion object {
        /** h:mm:ss, for messages and the crash log. */
        fun clock(ms: Long): String = (ms / 1000).let { s -> "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }
        /** Units a lane takes at a time when several work on one book: about two minutes of audio. */
        const val BATCH_UNITS = 4
    }
}
