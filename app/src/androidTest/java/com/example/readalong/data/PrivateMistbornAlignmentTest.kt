package com.example.readalong.data

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.domain.epub.EpubParser
import com.example.readalong.domain.alignment.EpubAligner
import com.example.readalong.domain.model.TranscriptSegment
import com.example.readalong.domain.reading.sentenceCues
import com.example.readalong.data.transcription.TimedRecognitionResult
import com.example.readalong.domain.transcription.TranscriptionInput
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Local-only user references and offline base.en output; never packaged in either APK. */
class PrivateMistbornAlignmentTest {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun actualPlayerSeekProducesTheCorrectAudioAtDistantPositions() = kotlinx.coroutines.runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateMp3Timeline") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val audio = File(File(app.cacheDir, "exports").apply { mkdirs() }, "player-mistborn.mp3")
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cat /sdcard/Download/readalong-mistborn/reference.mp3")).use { input -> audio.outputStream().use { input.copyTo(it, 65536) } }
            for (seconds in listOf(180, 600, 3600, 180)) {
                val captured = java.io.ByteArrayOutputStream()
                var rate = 0; var channels = 0
                val sink = object : androidx.media3.exoplayer.audio.TeeAudioProcessor.AudioBufferSink {
                    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                        assertEquals(androidx.media3.common.C.ENCODING_PCM_16BIT, encoding)
                        rate = sampleRateHz; channels = channelCount
                    }
                    override fun handleBuffer(buffer: java.nio.ByteBuffer) = synchronized(captured) {
                        if (captured.size() < rate * channels * 4) {
                            val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); captured.write(bytes)
                        }
                    }
                }
                val renderer = object : androidx.media3.exoplayer.DefaultRenderersFactory(app) {
                    override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean,
                        enableAudioOutputPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                        androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                            .setAudioProcessors(arrayOf(androidx.media3.exoplayer.audio.TeeAudioProcessor(sink))).build()
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    player = androidx.media3.exoplayer.ExoPlayer.Builder(app, renderer)
                        .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(app,
                            com.example.readalong.data.audio.AudiobookExtractorsFactory(app))).build().apply {
                        setMediaItem(androidx.media3.common.MediaItem.fromUri(audio.toURI().toString()))
                        val cue = com.example.readalong.domain.reading.SentenceCue(0, 1, seconds * 1000L, seconds * 1000L + 1000)
                        val onset = TranscriptSegment("onset", "test", "one", seconds * 1000L, seconds * 1000L + 1000, "Onset.",
                            words = listOf(com.example.readalong.domain.model.TranscriptWord("Onset.", seconds * 1000L, seconds * 1000L + 500)))
                        prepare(); seekTo(com.example.readalong.domain.reading.sentenceSeekStart(cue, listOf(onset))!!); play()
                    }
                }
                kotlinx.coroutines.withTimeout(20_000) {
                    while (synchronized(captured) { captured.size() } < rate * channels * 3 || rate == 0) kotlinx.coroutines.delay(50)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { player!!.release(); player = null }
                val pcm = java.nio.ByteBuffer.wrap(synchronized(captured) { captured.toByteArray() }).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val frames = pcm.remaining() / channels
                val actual = FloatArray(minOf(1500, frames * 1000 / rate)) { ms ->
                    val from = ms * rate / 1000; val to = (ms + 1) * rate / 1000
                    var sum = 0.0
                    for (frame in from until to) for (channel in 0 until channels) sum += pcm.get(frame * channels + channel) / 32768.0
                    (sum / ((to - from) * channels)).toFloat()
                }
                val referenceBuffer = java.nio.ByteBuffer.wrap(read("reference-$seconds.pcm")).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val reference = FloatArray(referenceBuffer.remaining()).also { referenceBuffer.get(it) }
                var best = -1.0; var lag = 0
                for (shift in -240..100) {
                    var aa = 0.0; var bb = 0.0; var ab = 0.0
                    for (i in 300 until minOf(actual.size - 100, 1200)) {
                        val a = actual[i].toDouble(); val b = reference[i + shift].toDouble()
                        aa += a * a; bb += b * b; ab += a * b
                    }
                    val score = ab / kotlin.math.sqrt(aa * bb)
                    if (score > best) { best = score; lag = shift }
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("player_seek", "${seconds}s: actual audio lag=${lag}ms correlation=$best") })
                assertTrue("Player sought to different audio at ${seconds}s: $best", best > .6)
                assertTrue("Sentence seek must include the onset, with at most 200ms lead-in at ${seconds}s: ${lag}ms", lag in -200..0)
            }
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { player?.release() }
            audio.delete()
        }
    }
    private fun read(name: String): ByteArray {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-mistborn/$name")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    @Test fun privateMp3DecoderAgreesWithSequentialReferenceAtDistantPositions() = kotlinx.coroutines.runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateMp3Timeline") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val audio = File(File(app.cacheDir, "exports").apply { mkdirs() }, "private-mistborn.mp3")
        try {
            val fd = instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-mistborn/reference.mp3")
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input -> audio.outputStream().use { input.copyTo(it) } }
            assertTrue(audio.length() > 1_000_000)
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", audio)
            for (seconds in listOf(0, 180, 600, 3600)) {
                val pcm = File(app.cacheDir, "mistborn-android-$seconds.pcm")
                val duration = com.example.readalong.data.transcription.AudioDecoder(app).decode(uri, seconds * 1000L, 30_000, pcm)
                assertTrue("Decoded duration at $seconds: $duration", duration in 29_900..30_000)
                // Independent reference is produced by decoding from byte zero with FFmpeg,
                // then selecting the interval. It does not trust either platform's seek table.
                fun floats(bytes: ByteArray): FloatArray {
                    val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    return FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
                try {
                    val actual = floats(pcm.readBytes()).asList().chunked(16).map { it.average().toFloat() }
                    val reference = floats(read("reference-$seconds.pcm"))
                    assertTrue(reference.size >= 12_000)
                    var best = -1.0; var lag = 0
                    // Permit sub-frame/decoder-delay differences, never a different sentence.
                    for (shift in -100..100) {
                        var aa = 0.0; var bb = 0.0; var ab = 0.0
                        for (i in 200 until 10_200) {
                            val a = actual[i].toDouble(); val b = reference[i + shift].toDouble()
                            aa += a * a; bb += b * b; ab += a * b
                        }
                        val score = ab / kotlin.math.sqrt(aa * bb)
                        if (score > best) { best = score; lag = shift }
                    }
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("mp3_timeline", "${seconds}s: reference difference ${lag}ms; correlation $best")
                    })
                    assertTrue("Wrong MP3 audio at ${seconds}s: correlation=$best", best > .65)
                    assertTrue("MP3 drift at ${seconds}s: ${lag}ms", kotlin.math.abs(lag) <= 75)
                } finally { pcm.delete() }
            }
        } finally { audio.delete() }
    }

    @Test fun actualDramatizedExcerptUsesDistinctSentenceTimesAcrossPublisherParagraphs() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateMistborn") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val file = File(app.cacheDir, "private-mistborn.epub")
        try {
            file.writeBytes(read("reference.epub"))
            val blocks = EpubParser().parse(file).blocks
            val speech = (180..330 step 30).flatMap { seconds -> TimedRecognitionResult.segments(String(read("mistborn-$seconds.json")),
                TranscriptionInput("private-mistborn", "first", "local", seconds * 1000L, (seconds + 30) * 1000L, false)) }
            assertTrue(speech.size >= 10)
            val sources = speech.associateBy { it.id }
            val result = EpubAligner().align(blocks, speech)
            var timed = 0; var corrected = 0; var rejectedPartial = 0; var maximumJump = 0L
            for (block in result.blocks.filter { it.anchors.isNotEmpty() }) {
                val p = TranscriptSegment("epub:${block.block.ordinal}", "private-mistborn", "first", -1, -1, block.block.text,
                    anchors = block.anchors, imported = true)
                val current = sentenceCues(p, sources)
                val legacy = sentenceCues(p.copy(anchors = p.anchors.map { it.copy(sourceStart = null, sourceEnd = null) }), sources)
                for ((index, cue) in current.withIndex()) {
                    val start = cue.audioStartMs ?: continue
                    timed++
                    assertTrue(start in 180_000..360_000)
                    val old = legacy[index].audioStartMs
                    if (old == null) rejectedPartial++
                    if (old != null && start - old > 1000) { corrected++; maximumJump = maxOf(maximumJump, start - old) }
                }
            }
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("mistborn_alignment", "${speech.size} spoken segments; ${result.matchedSegments} matched; $timed timed sentences; $rejectedPartial rejected partial legacy locators; $corrected formerly early seeks; largest correction ${maximumJump}ms")
            })
            assertTrue("The supplied excerpt must actually match the supplied full EPUB", result.matchedSegments >= 8)
            assertTrue("Split-paragraph source ranges must supply starts that incomplete legacy anchors cannot", rejectedPartial >= 1 || corrected >= 1 && maximumJump >= 2500)
            assertTrue(timed >= 10)
        } finally { file.delete() }
    }
}
