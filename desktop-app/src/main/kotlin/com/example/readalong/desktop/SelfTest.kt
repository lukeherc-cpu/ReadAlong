package com.example.readalong.desktop

import com.example.readalong.desktop.audio.AudioPlayer
import com.example.readalong.desktop.audio.AudioReader
import com.example.readalong.desktop.core.*
import com.example.readalong.desktop.reading.BookTextBuilder
import com.example.readalong.desktop.transcription.*
import kotlinx.coroutines.runBlocking
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import java.io.File
import java.nio.ShortBuffer

/**
 * `ReadAlong --self-test <book folder> --model <ggml model> [--device cpu|gpu] [--expect <word>] [--out <file>]`
 *
 * Runs the packaged app's whole path without a window: library scan, FFmpeg decoding, the transcriber
 * process with whisper.cpp on the chosen device, the phone's chunk format, reading the text back through
 * the phone's WhistleResult, a .readalong export and re-import, and the speed filter. Used by CI.
 */
object SelfTest {
    fun run(args: Array<String>): Int {
        fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val out = StringBuilder()
        fun log(line: String) { out.appendLine(line); System.err.println(line) }
        val home = File(System.getProperty("java.io.tmpdir"), "readalong-self-test-${System.nanoTime()}").apply { mkdirs() }
        System.setProperty("readalong.home", home.path)
        var ok = false
        try {
            ok = runBlocking { test(File(requireNotNull(arg("--self-test"))), File(requireNotNull(arg("--model"))), arg("--device") ?: "cpu", arg("--expect"), ::log) }
        } catch (failure: Throwable) {
            log("ERROR ${failure::class.simpleName}: ${failure.message}")
            failure.stackTrace.take(12).forEach { log("    at $it") }
        }
        log(if (ok) "PASS" else "FAIL")
        // PASS/FAIL first, so CI can check the first line.
        arg("--out")?.let { File(it).apply { parentFile?.mkdirs() }.writeText((if (ok) "PASS\n" else "FAIL\n") + out) }
        home.deleteRecursively()
        return if (ok) 0 else 1
    }

    private suspend fun test(folder: File, model: File, device: String, expect: String?, log: (String) -> Unit): Boolean {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val library = LibraryStore(scope = scope)
        val transcripts = TranscriptStore()
        val added = LibraryImporter(library, transcripts).add(listOf(folder), remember = false)
        val book = library.book(added.singleOrNull() ?: error("Expected one book in $folder, found ${added.size}")) ?: error("Book missing")
        log("book: ${book.title} · ${book.parts.size} file(s) · ${book.durationMs} ms · ${book.chapters.size} chapter(s)")

        val process = TranscriberProcess.start()
        val plan = transcriptionPlan(book)
        try {
            log("devices: " + process.devices.joinToString { "${it.type}:${it.description.ifBlank { it.name }} (${it.backend})" })
            log("system: ${process.systemInfo.trim()}")
            val heads = SpeechModels.all.firstOrNull { model.name == it.fileName }?.heads ?: 3
            val (name, onGpu) = process.load(model, heads, if (device == "gpu") "gpu" else "cpu")
            log("loaded on: $name (gpu=$onGpu)")
            if (device == "gpu" && !onGpu) { log("expected the GPU but whisper.cpp is on the CPU"); return false }
            transcripts.begin(book.id, "desktop:whisper.cpp:${model.nameWithoutExtension.removePrefix("ggml-")}", "Whisper", plan.size)
            val started = System.nanoTime()
            for (file in plan.map { it.file }.distinct()) {
                val units = plan.filter { it.file == file }
                process.transcribe(File(book.parts[file].path), units.map { it.startMs to it.endMs }, "en", 4, if (onGpu) 5 else 1, { _, _ -> }) { i, result, ms ->
                    log("unit ${units[i].startMs}-${units[i].endMs} ms in $ms ms: ${result.optString("text").take(120)}")
                    transcripts.append(book.id, Chunk(file, units[i].startMs, units[i].endMs, result), plan.size)
                }
            }
            log("transcribed ${book.durationMs} ms of audio in ${(System.nanoTime() - started) / 1_000_000} ms")
        } finally { process.close() }

        // Read the text the way the reader does: chunk results through the phone's WhistleResult.
        val segments = transcripts.segments(book)
        val text = BookTextBuilder.generated(book, segments)
        val words = segments.flatMap { it.words }
        log("segments: ${segments.size}, words: ${words.size}, paragraphs: ${text.paragraphs.size}")
        val timed = text.paragraphs.flatMap { it.cues }.count { it.audioStartMs != null }
        log("timed sentences: $timed of ${text.paragraphs.sumOf { it.cues.size }}")
        val all = segments.joinToString(" ") { it.text }
        log("text: ${all.take(400)}")
        var pass = segments.isNotEmpty() && words.isNotEmpty() && timed > 0
        if (expect != null && !all.contains(expect, ignoreCase = true)) { log("expected \"$expect\" in the text"); pass = false }
        if (!words.zipWithNext().all { (a, b) -> b.startMs >= a.startMs }) { log("word times go backwards"); pass = false }

        // A package written here must read back identically (the phone reads the same two files).
        val pkg = File(folder, "${folder.name}.readalong")
        transcripts.exportPackage(book, pkg)
        val before = segments.map { Triple(it.startMs, it.endMs, it.text) }
        transcripts.remove(book.id)
        transcripts.importPackage(book, pkg)
        val after = transcripts.segments(book).map { Triple(it.startMs, it.endMs, it.text) }
        log("package: ${pkg.length()} bytes, round trip ${if (before == after) "identical" else "DIFFERENT"}")
        if (before != after) pass = false
        java.util.zip.ZipFile(pkg).use { zip ->
            val names = zip.entries().toList().map { it.name }
            if (names != listOf("manifest.json", "chunks.jsonl")) { log("package entries: $names"); pass = false }
            log("manifest: " + zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText().replace(Regex("\\s+"), " ").take(400))
        }

        // The player's speed change: 1.5× should turn 4 s of audio into about 2.67 s, at the same pitch.
        AudioReader(File(book.parts.first().path), AudioPlayer.RATE, 2).use { reader ->
            val filter = FFmpegFrameFilter(AudioPlayer.atempo(1.5f), 2).apply { sampleRate = AudioPlayer.RATE; sampleFormat = avutil.AV_SAMPLE_FMT_S16; start() }
            var input = 0L; var output = 0L
            while (input < AudioPlayer.RATE * 4L) {
                val samples = reader.readShorts() ?: break
                input += samples.size / 2
                filter.pushSamples(0, 2, AudioPlayer.RATE, avutil.AV_SAMPLE_FMT_S16, ShortBuffer.wrap(samples))
                while (true) { val f = filter.pullSamples() ?: break; output += ((f.samples[0] as ShortBuffer).remaining() / 2) }
            }
            filter.stop(); filter.release()
            val ratio = input.toDouble() / output.coerceAtLeast(1)
            log("speed 1.5×: $input frames in, $output out, ratio %.2f".format(ratio))
            if (ratio !in 1.3..1.7) pass = false
        }
        return pass
    }
}
