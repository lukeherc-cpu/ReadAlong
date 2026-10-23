package com.example.readalong.data

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.epub.GeneratedEpubRepository
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.data.reading.TranscriptRepository
import com.example.readalong.data.reading.toEntity
import com.example.readalong.data.transcription.*
import com.example.readalong.domain.alignment.EpubAligner
import com.example.readalong.domain.epub.EpubParser
import com.example.readalong.domain.model.TranscriptSegment
import com.example.readalong.domain.reading.*
import com.example.readalong.domain.transcription.TranscriptionInput
import com.example.readalong.ui.reading.ReadingViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** User-owned references stay on the test device; no book assets are packaged in the APK. */
class AdditionalLibraryTest {
    private val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun copy(path: String, destination: File) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "cat /sdcard/Download/readalong-library-tests/$path")).use { input -> destination.outputStream().use { input.copyTo(it, 65536) } }
    }
    private fun text(path: String): String {
        val file = File(app.cacheDir, "library-json-${System.nanoTime()}")
        try { copy(path, file); return file.readText() } finally { file.delete() }
    }
    private fun report(key: String, result: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("library_$key", result) })

    @Test fun suppliedEditionsAndGeneratedTextSeekToTheSameBeginningFromEveryPartOfASentence() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateLibrary") == "true")
        val manifest = JSONArray(text("manifest.json"))
        assertEquals(9, manifest.length())
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            for (i in 0 until manifest.length()) {
                val book = manifest.getJSONObject(i); val key = book.getString("key")
                db.bookDao().insertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = key, isDemo = false)))
                val samples = book.getJSONArray("samples")
                val speech = (0 until samples.length()).flatMap { n ->
                    val sample = samples.getJSONObject(n); val start = sample.getLong("seconds") * 1000
                    WhistleResult.segments(text(sample.getString("json")), TranscriptionInput(key, "one", "local", start, start + 30_000, false))
                }
                assertTrue("$key needs recognized words", speech.sumOf { it.words.size } > 100)
                db.readingDao().putSegments(speech.map { it.toEntity() })
                val sources = speech.associateBy { it.id }
                var coverage = 1f
                val paragraphs = if (!book.isNull("epub")) {
                    val epub = File(app.cacheDir, "library-$key.epub")
                    try {
                        copy("$key/reference.epub", epub)
                        val result = EpubAligner().align(EpubParser().parse(epub).blocks, speech)
                        coverage = result.coverage
                        result.blocks.filter { it.anchors.isNotEmpty() }.map { block ->
                            TranscriptSegment("epub:library:$key:${block.block.ordinal}", key, "one",
                                block.anchors.minOf { it.startMs }, block.anchors.maxOf { it.endMs }, block.block.text,
                                anchors = block.anchors, imported = true)
                        }
                    } finally { epub.delete() }
                } else speech
                val model = ReadingViewModel(key, db, TranscriptRepository(db), app.readerPreferences, GeneratedEpubRepository(app, db),
                    com.example.readalong.data.processing.ProcessingRepository(app, db, app.speechModel))
                models.put(key, model)
                var tested = 0; var partial = 0
                for (paragraph in paragraphs) for (cue in sentenceCues(paragraph, sources)) {
                    val onset = cue.audioStartMs
                    if (onset == null) { partial++; continue }
                    val expected = sentenceSeekStart(cue, speech)!!
                    assertTrue("$key must preserve the onset", expected in (onset - 160).coerceAtLeast(0)..onset)
                    val targets = listOf(cue.start, (cue.start + cue.end) / 2, cue.end - 1).map { model.sentenceTarget(paragraph, it) }
                    assertEquals("$key tapped start/middle/end must seek the same opening", listOf(expected, expected, expected), targets)
                    assertTrue("$key must highlight the sentence at its spoken opening", cue.activeAt(onset))
                    tested++
                }
                report(key, "${speech.size} recognized sentences; $tested verified starts; $partial partial locators withheld; coverage=$coverage; originalEPUB=${!book.isNull("epub")}")
                assertTrue("$key must provide useful, non-guessed sentence starts", tested >= 5)
            }
        } finally { withContext(Dispatchers.Main) { models.clear() }; db.close() }
    }

    @Test fun originalAudioDecoderAndAndroidWhistleAgreeWithSequentialReference() = runBlocking {
        val key = InstrumentationRegistry.getArguments().getString("libraryBook")
        assumeTrue(key != null)
        val book = JSONArray(text("manifest.json")).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("key") == key } }
        val audio = File(File(app.cacheDir, "exports").apply { mkdirs() }, "library-original.${if (book.getString("audio").endsWith(".mp3")) "mp3" else "m4b"}")
        val pcm = File(app.cacheDir, "library-decoded.pcm")
        val referenceFile = File(app.cacheDir, "library-reference.pcm")
        try {
            copy("original-audio", audio); copy("$key/reference-600.pcm", referenceFile)
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", audio)
            val duration = AudioDecoder(app).decode(uri, 600_000, 30_000, pcm)
            assertTrue("$key full audio must decode", duration >= 29_900)
            fun floats(file: File): FloatArray {
                val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                return FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
            fun milliseconds(samples: FloatArray) = samples.asList().chunked(16).map { it.average().toFloat() }
            val actual = milliseconds(floats(pcm)); val reference = milliseconds(floats(referenceFile))
            var best = -1.0; var lag = 0
            for (shift in -100..100) {
                var aa = 0.0; var bb = 0.0; var ab = 0.0
                for (n in 200 until 10_200) {
                    val a = actual[n].toDouble(); val b = reference[n + shift].toDouble()
                    aa += a*a; bb += b*b; ab += a*b
                }
                val score = ab / sqrt(aa*bb)
                if (score > best) { best = score; lag = shift }
            }
            report("${key}_decoder", "600s independent audio correlation=$best; clock lag=${lag}ms")
            assertTrue("$key decoder must produce the correct spoken audio", best > .6 && abs(lag) <= 100)
            if (key == "circe") {
                assertTrue("Circe's old saved timestamps must receive the one-time repair prompt",
                    uri.toString() in com.example.readalong.data.audio.Mp3Timing.correctedUris.value)
            }
            val speech = WhistleTranscriptionEngine(app, app.speechModel).transcribe(TranscriptionInput("native-$key", "one", uri.toString(), 600_000, 630_000, false, 600_000)).toList()
            val host = WhistleResult.segments(text("$key/600.json"), TranscriptionInput("host-$key", "one", "local", 600_000, 630_000, false))
            val a = speech.flatMap { it.words }; val b = host.flatMap { it.words }
            fun word(text: String) = text.filter { it.isLetterOrDigit() }.lowercase()
            val matches = a.mapNotNull { w -> b.filter { word(it.word) == word(w.word) }.minByOrNull { abs(it.startMs-w.startMs) }?.takeIf { abs(it.startMs-w.startMs) <= 320 } }
            report("${key}_native", "${a.size} Android words; ${matches.size} independently windowed host matches within 320ms")
            assertTrue("$key Android model must retain canonical word timing", matches.size >= a.size * .75)
            val selected = host.withIndex().firstOrNull { (i, segment) -> i > 0 && segment.startMs - host[i-1].endMs >= 300 && segment.startMs < 625_000 }?.value
                ?: host.firstOrNull { it.startMs >= 601_000 && it.startMs < 625_000 } ?: host.first()
            val cue = sentenceCues(selected).first()
            val seek = sentenceSeekStart(cue, host)!!
            val rendered = capturePlaybackAudio(app, uri, seek)
            val base = (seek - 600_000).toInt()
            best = -1.0; lag = 0
            for (shift in -100..100) {
                var aa = 0.0; var bb = 0.0; var ab = 0.0
                for (n in 300 until 1300) {
                    val x = rendered[n].toDouble(); val y = reference[base + n + shift].toDouble()
                    aa += x*x; bb += y*y; ab += x*y
                }
                val score = ab / sqrt(aa*bb)
                if (score > best) { best = score; lag = shift }
            }
            report("${key}_player", "sentence onset=${cue.audioStartMs}ms; seek=${seek}ms; rendered correlation=$best; seek lag=${lag}ms")
            assertTrue("$key sentence tap must play the correct audio, not just report the right position", best > .6 && abs(lag) <= 100)
            assertTrue("$key sentence seek must retain the first syllable", seek + lag <= cue.audioStartMs!! + 80)
        } finally { audio.delete(); pcm.delete(); referenceFile.delete() }
    }
}
