package com.example.readalong.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.transcription.AudioDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Supplied book stays in external test storage and is never packaged in either APK. */
class YesteryearCompatibilityTest {
    @Test fun suppliedM4bDecodesOpeningChapterBoundariesAndDeepSeek() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateYesteryear") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val file = File(app.getExternalFilesDir(null), "verification/Yesteryear.m4b")
        assertTrue(file.length() > 1000)
        val uri = android.net.Uri.fromFile(file)
        val native = android.media.MediaExtractor()
        try {
            native.setDataSource(app, uri, null)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("native_tracks", native.trackCount.toString()) })
        } finally { native.release() }
        val metadata = android.media.MediaMetadataRetriever()
        try {
            metadata.setDataSource(app, uri)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("native_duration", metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)) })
        } finally { metadata.release() }
        val importedDb = androidx.room.Room.inMemoryDatabaseBuilder(app, com.example.readalong.data.local.ReadAlongDatabase::class.java).build()
        try {
            val result = com.example.readalong.data.importing.AudioImporter(app, importedDb).importFiles(listOf(uri), false)
            val id = result.bookIds.single()
            assertEquals(49_634_779L, importedDb.mediaDao().audioFiles(id).single().durationMs)
            assertEquals(62, importedDb.mediaDao().chapters(id).size)
            assertTrue(com.example.readalong.data.processing.TranscriptionPlan.inputs(importedDb, id).isNotEmpty())
        } finally { importedDb.close() }
        val pcm = File(app.cacheDir, "yesteryear-check.pcm")
        try {
            for ((start, length) in listOf(0L to 16_439L, 16_400L to 39L, 16_439L to 30_000L, 3_600_000L to 30_000L, 46_800_000L to 30_000L)) {
                val began = android.os.SystemClock.elapsedRealtime()
                val decoded = AudioDecoder(app).decode(uri, start, length, pcm)
                assertTrue("At $start expected $length ms, got $decoded", decoded in (length - 30).coerceAtLeast(1)..length)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("decoded_interval", "$start:$length:$decoded:${android.os.SystemClock.elapsedRealtime()-began}") })
                if (start == 0L) pcm.copyTo(File(app.getExternalFilesDir(null), "verification/yesteryear-opening.pcm"), overwrite = true)
            }
            if (InstrumentationRegistry.getArguments().getString("whistleModel") == "true") {
                val segments = kotlinx.coroutines.withTimeout(180_000) {
                    com.example.readalong.data.transcription.WhistleTranscriptionEngine(app, app.speechModel).transcribe(
                        com.example.readalong.domain.transcription.TranscriptionInput("yesteryear-probe", "intro", uri.toString(), 0, 16_439, false)).toList()
                }
                assertTrue("The supplied book must reach Whistle and produce timed text", segments.isNotEmpty())
                assertTrue(segments.all { it.startMs >= 0 && it.endMs <= 16_439 })
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("whistle_segments", segments.size.toString()) })
            }
        } finally { pcm.delete() }
    }
}
