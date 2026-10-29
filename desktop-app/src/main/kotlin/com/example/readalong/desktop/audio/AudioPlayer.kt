package com.example.readalong.desktop.audio

import com.example.readalong.desktop.core.LibraryBook
import com.example.readalong.desktop.core.LibraryStore
import com.example.readalong.desktop.core.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import java.io.File
import java.nio.ShortBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.*
import kotlin.math.log10

data class PlaybackState(
    val bookId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val sleepEndsAt: Long? = null,
    val sleepAtChapterEnd: Boolean = false,
    val error: String? = null,
)

/**
 * Plays a book's files as one timeline: FFmpeg decodes, FFmpeg's atempo changes speed without
 * changing pitch, and Java Sound plays. Its own thread keeps decoding off the UI; [positionNow]
 * reads the sound card's clock, so the text highlight moves exactly with the voice.
 */
class AudioPlayer(private val scope: CoroutineScope, private val library: LibraryStore, private val settings: SettingsStore) {
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState

    private sealed interface Command
    private data class Open(val book: LibraryBook, val positionMs: Long, val play: Boolean) : Command
    private data class Seek(val positionMs: Long) : Command
    private data class Play(val play: Boolean) : Command
    private data class Speed(val speed: Float) : Command
    private data object Close : Command
    private val commands = LinkedBlockingQueue<Command>()

    @Volatile private var book: LibraryBook? = null
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var sourceEndMs = 0L       // book position just after the last sample written
    @Volatile private var writtenFrames = 0L     // in the line's frame count
    @Volatile private var seekFloorMs = 0L
    @Volatile private var speed = 1f
    @Volatile private var playing = false

    init {
        Thread(::loop, "readalong-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1 }.start()
        // Save the listening position and run the sleep timer.
        scope.launch {
            while (isActive) {
                delay(500)
                val current = mutableState.value
                val id = current.bookId ?: continue
                if (!current.playing) continue
                val position = positionNow()
                mutableState.update { it.copy(positionMs = position) }
                library.update(id) { it.copy(positionMs = position, lastPlayedAt = System.currentTimeMillis(),
                    finished = it.finished || position >= it.durationMs - 1000) }
                val sleep = current.sleepEndsAt
                if (sleep != null && System.currentTimeMillis() >= sleep) { pause(); mutableState.update { it.copy(sleepEndsAt = null) } }
                if (current.sleepAtChapterEnd) {
                    val b = library.book(id)
                    val chapter = b?.chapterAt(position) ?: -1
                    val next = b?.chapters?.getOrNull(chapter + 1)?.startMs
                    if (next != null && position >= next - 400) { pause(); mutableState.update { it.copy(sleepAtChapterEnd = false) } }
                }
            }
        }
    }

    fun open(bookId: String, play: Boolean = true, positionMs: Long? = null) {
        val b = library.book(bookId) ?: return
        val start = positionMs ?: if (b.finished || b.positionMs >= b.durationMs - 2000) 0 else b.positionMs
        if (b.finished && positionMs == null) library.update(bookId) { it.copy(finished = false) }
        speed = b.speed
        mutableState.value = PlaybackState(bookId, play, start, b.durationMs, b.speed)
        commands += Open(b, start, play)
    }
    fun toggle() { if (mutableState.value.playing) pause() else play() }
    fun play() {
        val id = mutableState.value.bookId ?: return
        if (book == null) { open(id); return }
        mutableState.update { it.copy(playing = true, error = null) }; commands += Play(true)
        library.update(id) { it.copy(lastPlayedAt = System.currentTimeMillis()) }
    }
    fun pause() {
        val position = positionNow()
        mutableState.update { it.copy(playing = false, positionMs = position) }; commands += Play(false)
        mutableState.value.bookId?.let { id -> library.update(id) { it.copy(positionMs = position) } }
    }
    fun seekTo(positionMs: Long) {
        val b = book ?: library.book(mutableState.value.bookId ?: return) ?: return
        val target = positionMs.coerceIn(0, (b.durationMs - 250).coerceAtLeast(0))
        seekFloorMs = target; sourceEndMs = target
        mutableState.update { it.copy(positionMs = target) }
        library.update(b.id) { it.copy(positionMs = target) }
        commands += Seek(target)
    }
    fun skip(deltaMs: Long) = seekTo(positionNow() + deltaMs)
    fun setSpeed(value: Float) {
        val s = value.coerceIn(0.5f, 3f)
        mutableState.update { it.copy(speed = s) }
        mutableState.value.bookId?.let { id -> library.update(id) { it.copy(speed = s) } }
        commands += Speed(s)
    }
    fun setVolume(volume: Float) { settings.update { it.copy(volume = volume.coerceIn(0f, 1f)) }; applyVolume() }
    fun sleepAfter(minutes: Int?) = mutableState.update { it.copy(sleepEndsAt = minutes?.let { m -> System.currentTimeMillis() + m * 60_000L }, sleepAtChapterEnd = false) }
    fun sleepAtChapterEnd() = mutableState.update { it.copy(sleepEndsAt = null, sleepAtChapterEnd = true) }
    fun close() { mutableState.value.bookId?.let { id -> val p = positionNow(); library.update(id) { it.copy(positionMs = p) } }; commands += Close; mutableState.value = PlaybackState() }

    /** The book position being heard right now, from the sound card's own clock. */
    fun positionNow(): Long {
        val l = line ?: return mutableState.value.positionMs
        if (!playing) return maxOf(seekFloorMs, sourceEndMs - bufferedMs(l))
        return maxOf(seekFloorMs, sourceEndMs - bufferedMs(l))
    }
    private fun bufferedMs(l: SourceDataLine) = ((writtenFrames - l.longFramePosition).coerceAtLeast(0) * 1000 / RATE * speed).toLong()

    private fun applyVolume() {
        val control = line?.takeIf { it.isControlSupported(FloatControl.Type.MASTER_GAIN) }?.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl ?: return
        val volume = settings.value.volume.coerceIn(0.0001f, 1f)
        control.value = (20 * log10(volume)).coerceIn(control.minimum, control.maximum)
    }

    // ---- the audio thread ----------------------------------------------------------------------

    private var reader: AudioReader? = null
    private var readerPart = -1
    private var filter: FFmpegFrameFilter? = null
    private var position = 0L // book position of the next sample to decode

    private fun loop() {
        while (true) {
            val command = if (playing && book != null) commands.poll() else commands.poll(200, TimeUnit.MILLISECONDS)
            try {
                when (command) {
                    is Open -> { book = command.book; speed = command.book.speed; closeReader(); position = command.positionMs; seekFloorMs = position; sourceEndMs = position
                        openLine(); flush(); playing = command.play; if (playing) line?.start() else line?.stop() }
                    is Seek -> { closeReader(); position = command.positionMs; seekFloorMs = position; flush() }
                    is Play -> { playing = command.play; if (playing) line?.start() else line?.stop() }
                    is Speed -> { if (speed != command.speed) { val now = positionNow(); speed = command.speed; closeFilter(); closeReader(); position = now; seekFloorMs = now; flush() } }
                    Close -> { playing = false; book = null; closeReader(); closeFilter(); line?.let { it.stop(); it.flush(); it.close() }; line = null; continue }
                    null -> {}
                }
                if (playing && book != null) pump()
            } catch (failure: Throwable) {
                playing = false
                runCatching { line?.stop() }
                mutableState.update { it.copy(playing = false, error = failure.message ?: "This audio couldn't be played.") }
            }
        }
    }

    private fun openLine() {
        if (line != null) return
        val format = AudioFormat(RATE.toFloat(), 16, 2, true, false)
        line = (AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine).apply {
            open(format, RATE * 4 / 5) // 200 ms: enough to never stutter, short enough that pausing is immediate.
        }
        writtenFrames = 0
        applyVolume()
    }

    private fun flush() {
        val l = line ?: return
        l.flush()
        writtenFrames = l.longFramePosition
        sourceEndMs = position
    }

    /** Decodes and writes about 40 ms of audio. */
    private fun pump() {
        val b = book ?: return
        val l = line ?: return
        val part = b.partAt(position)
        if (reader == null || readerPart != part) {
            closeReader()
            reader = AudioReader(File(b.parts[part].path), RATE, 2).also { r ->
                val inFile = position - b.parts[part].offsetMs
                if (inFile > 50) r.seek(inFile)
            }
            readerPart = part
        }
        val samples = reader!!.readShorts()
        if (samples == null) {
            if (part + 1 < b.parts.size) { position = b.parts[part + 1].offsetMs; closeReader(); return }
            // The end of the book: let the buffer play out, then stop.
            l.drain(); playing = false; l.stop()
            mutableState.update { it.copy(playing = false, positionMs = b.durationMs) }
            library.update(b.id) { it.copy(positionMs = b.durationMs, finished = true) }
            return
        }
        val frames = samples.size / 2
        val sourceMs = frames * 1000L / RATE
        val output = if (speed == 1f) samples else tempo(samples)
        val bytes = ByteArray(output.size * 2)
        for (i in output.indices) { val v = output[i].toInt(); bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte() }
        position += sourceMs
        var offset = 0
        while (offset < bytes.size) {
            if (commands.isNotEmpty()) return // a seek or pause discards the rest of this piece
            val n = l.write(bytes, offset, minOf(bytes.size - offset, 8192))
            offset += n
            writtenFrames += n / 4
            sourceEndMs = position - ((bytes.size - offset) / 4 * 1000L / RATE * speed).toLong()
        }
        sourceEndMs = position
    }

    private fun tempo(samples: ShortArray): ShortArray {
        val f = filter ?: FFmpegFrameFilter(atempo(speed), 2).apply {
            sampleRate = RATE; sampleFormat = avutil.AV_SAMPLE_FMT_S16; start()
        }.also { filter = it }
        f.pushSamples(0, 2, RATE, avutil.AV_SAMPLE_FMT_S16, ShortBuffer.wrap(samples))
        val out = ArrayList<ShortArray>()
        while (true) {
            val frame = f.pullSamples() ?: break
            val buffer = frame.samples?.firstOrNull() as? ShortBuffer ?: break
            out += ShortArray(buffer.remaining()).also { buffer.get(it) }
        }
        return if (out.size == 1) out[0] else ShortArray(out.sumOf { it.size }).also { all -> var at = 0; out.forEach { it.copyInto(all, at); at += it.size } }
    }

    private fun closeReader() { reader?.close(); reader = null; readerPart = -1; closeFilter() }
    private fun closeFilter() { filter?.let { runCatching { it.stop() }; runCatching { it.release() } }; filter = null }

    companion object {
        const val RATE = 44_100
        /** atempo takes 0.5 to 2 per stage; chain stages beyond that. */
        fun atempo(speed: Float): String {
            val stages = mutableListOf<Float>(); var s = speed
            while (s > 2f) { stages += 2f; s /= 2f }
            while (s < .5f) { stages += .5f; s /= .5f }
            stages += s
            return stages.joinToString(",") { "atempo=%.4f".format(java.util.Locale.ROOT, it) }
        }
    }
}
