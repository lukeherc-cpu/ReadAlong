package com.example.readalong.data

import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.transcription.AudioDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional private fixture stays outside both application and test APKs. */
class AudioFileCompatibilityTest {
    @Test fun privateM4bDecodesItsOpeningAndLaterChapters() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateM4b") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val audio = File(File(app.cacheDir, "exports").apply { mkdirs() }, "private-compatibility.m4b")
        val pcm = File(app.cacheDir, "private-compatibility.pcm")
        try {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-compatibility.m4b")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { source -> audio.outputStream().use { source.copyTo(it) } }
            assertTrue(audio.length() > 1000)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", audio)
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(app, uri, null)
                val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                extractor.selectTrack(track); extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val firstTime = extractor.sampleTime
                val size = extractor.readSampleData(java.nio.ByteBuffer.allocate(65536), 0)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("private_audio_packet", "First AAC packet: PTS=$firstTime us, bytes=$size")
                })
            } finally { extractor.release() }
            for (start in listOf(0L, 300_000L, 3_600_000L)) {
                val duration = AudioDecoder(app).decode(uri, start, 30_000, pcm)
                assertTrue("Decoded duration at $start was $duration", duration in 29_950..30_000)
            }
            if (InstrumentationRegistry.getArguments().getString("whistleModel") == "true") {
                val segments = kotlinx.coroutines.withTimeout(180_000) {
                    com.example.readalong.data.transcription.WhistleTranscriptionEngine(app, app.speechModel,
                        com.example.readalong.data.transcription.SpeechModelStore.ID)
                        .transcribe(com.example.readalong.domain.transcription.TranscriptionInput("compatibility", "opening", uri.toString(), 0, 30_000, false)).toList()
                }
                assertTrue("The actual supplied M4B must reach Whistle and produce text", segments.isNotEmpty())
                assertTrue(segments.all { it.startMs >= 0 && it.endMs <= 30_000 })
            }
        } finally { audio.delete(); pcm.delete() }
    }
}
