package com.example.readalong.data

import androidx.test.core.app.ApplicationProvider
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.transcription.AudioDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AudioBoundaryRecoveryTest {
    @Test fun aacMp3AndFragmentedMp4PreserveShortChapterEdges() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        for (name in listOf("chapter-edges.m4a", "chapter-edges.mp3", "fragmented.m4a")) {
            val audio = File(File(app.cacheDir, "exports").apply { mkdirs() }, "synthetic-$name")
            val pcm = File(app.cacheDir, "synthetic-edge.pcm")
            try {
                assets.open("decoder/$name").use { input -> audio.outputStream().use { input.copyTo(it) } }
                val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", audio)
                for ((start, duration) in listOf(0L to 1L, 961L to 39L, 999L to 1L, 1000L to 30L)) {
                    val decoded = try { AudioDecoder(app).decode(uri, start, duration, pcm) }
                        catch (failure: Exception) { throw AssertionError("$name at $start for $duration ms", failure) }
                    if (start == 0L && decoded == 0L) assertEquals(0L, pcm.length())
                    else assertEquals("$name at $start", duration, decoded)
                }
            } finally { audio.delete(); pcm.delete() }
        }
    }

    @Test fun shortChapterEdgesDecodeAndOnlyProvenTinyEofTailCanBeEmpty() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val wave = createTestWave(app, "boundary-${System.nanoTime()}.wav", 1)
        val pcm = File(app.cacheDir, "boundary-output.pcm")
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", wave)
            for ((start, duration) in listOf(0L to 1L, 961L to 39L, 999L to 1L))
                assertEquals(duration, AudioDecoder(app).decode(uri, start, duration, pcm))
            assertEquals(0L, AudioDecoder(app).decode(uri, 1000, 10, pcm))
            assertTrue(runCatching { AudioDecoder(app).decode(uri, 1000, 30_000, pcm) }.isFailure)
        } finally { wave.delete(); pcm.delete() }
    }
}
