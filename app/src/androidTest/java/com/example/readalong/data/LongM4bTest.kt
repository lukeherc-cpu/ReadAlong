package com.example.readalong.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.transcription.AudioDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.example.readalong.data.local.*
import com.example.readalong.data.processing.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in private long-file probe. No audiobook media is included in test APKs. */
class LongM4bTest {
    @Test fun deepSeekLeavesRoomForReaderAndAlignmentMemory() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("longM4b") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val file = File(app.getExternalFilesDir(null), "verification/monte-cristo.m4b")
        val pcm = File(app.cacheDir, "long-m4b-pressure.pcm")
        val readerMemory = ByteArray(64 * 1024 * 1024) { 1 }
        try {
            System.gc()
            val runtime = Runtime.getRuntime()
            val before = runtime.totalMemory() - runtime.freeMemory()
            assertEquals(30_000L, AudioDecoder(app).decode(android.net.Uri.fromFile(file), 97_200_000, 30_000, pcm))
            val extra = runtime.totalMemory() - runtime.freeMemory() - before
            assertTrue("AAC loading added $extra heap bytes", extra < 16 * 1024 * 1024)
            assertEquals(1, readerMemory.first().toInt())
            assertEquals(1, readerMemory.last().toInt())
        } finally { pcm.delete() }
    }

    @Test fun realWhistleResumesSaved27HourProgressAndNotificationResumeAdvancesAgain() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("longM4b") == "true" && args.getString("whistleModel") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        assumeTrue(app.speechModel.available())
        assumeTrue(app.database.processingDao().unfinished().isEmpty())
        val id = "long-resume-${System.nanoTime()}"
        val preferences = app.appPreferences.preferences.first()
        val oldQueue = app.database.bookDao().metadata("transcription_queue_running")
        val file = File(app.getExternalFilesDir(null), "verification/monte-cristo.m4b")
        val scenario = androidx.test.core.app.ActivityScenario.launch(com.example.readalong.MainActivity::class.java)
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        try {
            app.appPreferences.update { it.copy(chargingOnly = false, setupStage = 3, batteryWarningSeen = true, homeGuideHidden = true, playerGuideHidden = true, readerGuideHidden = true) }
            app.database.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, title = "Long-book resume verification", isDemo = false, durationMs = 200_297_508)))
            app.database.mediaDao().putAudio(listOf(AudioFileEntity("$id-audio", id, android.net.Uri.fromFile(file).toString(), "Long audiobook", 0, 200_297_508, null)))
            val plan = TranscriptionPlan.encode(TranscriptionPlan.inputs(app.database, id))
            val count = TranscriptionPlan.decode(plan).size
            app.database.readingDao().putSegments(listOf(TranscriptEntity("$id-earlier", id, "$id-audio", 0, 1000, "Earlier saved text stays intact.", 1f, null)))
            app.database.readingDao().putVersion(ReadableVersionEntity(id, "PARTIAL_READY", null, 0, 1, null, 0, transcriptRevision = 1, activeEditionId = "live-$id", generatedFormat = 1))
            app.database.bookDao().putMetadata(LibraryMetadataEntity("live_reading_revision:$id", "live-$id:1"))
            app.database.processingDao().put(ProcessingJobEntity(id, "old-checkpoint", com.example.readalong.data.transcription.SpeechModelStore.ID,
                "CANCELLED", 3240, count, "Paused", 0, plan, TranscriptionPlan.fingerprint(app.database, id)))
            val began = android.os.SystemClock.elapsedRealtime()
            app.processing.transcribeNext(id)
            withTimeout(180_000) {
                while (true) {
                    val job = app.database.processingDao().find(id)!!
                    assertNotEquals(job.message, "FAILED", job.state)
                    if (job.completedUnits >= 3242) break
                    delay(50)
                }
            }
            val firstElapsed = android.os.SystemClock.elapsedRealtime() - began
            app.processing.cancel(id)
            withTimeout(15_000) { while (AudiobookTranscriptionService.activeBook == id) delay(50) }
            val paused = app.database.processingDao().find(id)!!
            assertEquals(plan, paused.planJson)
            assertNotNull(app.database.readingDao().segment("$id-earlier"))
            val canonical = app.database.readingDao().segments(id)
            assertTrue(canonical.any { it.startMs >= 97_200_000 && it.wordsJson != null })
            shell("input keyevent KEYCODE_HOME")
            com.example.readalong.data.transcription.TranscriptionNotifications.resumeAction(app, id, "Long-book resume verification").send()
            withTimeout(180_000) {
                while (app.database.processingDao().find(id)!!.completedUnits <= paused.completedUnits) {
                    val job = app.database.processingDao().find(id)!!
                    assertNotEquals(job.message, "FAILED", job.state)
                    delay(50)
                }
            }
            val resumed = app.database.processingDao().find(id)!!
            assertEquals(plan, resumed.planJson)
            val report = org.json.JSONObject().put("initial_checkpoint", 3240).put("paused_checkpoint", paused.completedUnits)
                .put("resumed_checkpoint", resumed.completedUnits).put("two_chunks_elapsed_ms", firstElapsed)
                .put("total_elapsed_ms", android.os.SystemClock.elapsedRealtime() - began)
            File(app.getExternalFilesDir(null), "verification/long-m4b-service-resume.json").writeText(report.toString(2))
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("real_resume", report.toString()) })
        } finally {
            app.processing.cancel(id)
            withTimeoutOrNull(15_000) { while (AudiobookTranscriptionService.activeBook == id) delay(50) }
            app.database.bookDao().removeBook(id)
            app.appPreferences.update { preferences }
            if (oldQueue == null) app.database.bookDao().removeMetadata("transcription_queue_running")
            else app.database.bookDao().putMetadata(LibraryMetadataEntity("transcription_queue_running", oldQueue))
            shell("input keyevent KEYCODE_WAKEUP")
            scenario.close()
        }
    }

    @Test fun suppliedLongM4bDecodesAround27HoursAndNearItsEnd() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("longM4b") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val file = File(app.getExternalFilesDir(null), "verification/monte-cristo.m4b")
        assertTrue(file.isFile && file.length() > 1_000_000_000)
        val pcm = File(app.cacheDir, "long-m4b-probe.pcm")
        val measurements = org.json.JSONArray()
        val starts = args.getString("starts")?.split(',')?.map { it.toLong() }
            ?: listOf(0L, 3_600_000L, 97_200_000L, 97_410_000L, 176_400_000L, 200_250_000L)
        try {
            for (start in starts) {
                System.gc()
                val runtime = Runtime.getRuntime()
                val before = runtime.totalMemory() - runtime.freeMemory()
                val began = android.os.SystemClock.elapsedRealtime()
                val decoded = AudioDecoder(app).decode(android.net.Uri.fromFile(file), start, 30_000, pcm)
                val row = org.json.JSONObject().put("start_ms", start).put("decoded_ms", decoded)
                    .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - began)
                    .put("heap_before", before).put("heap_after", runtime.totalMemory() - runtime.freeMemory())
                    .put("heap_max", runtime.maxMemory())
                measurements.put(row)
                File(app.getExternalFilesDir(null), "verification/long-m4b-${args.getString("label", "probe")}.json").writeText(measurements.toString(2))
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("interval", row.toString()) })
                assertTrue("Missing audio at $start: $decoded ms", decoded in 29_950..30_000)
                pcm.copyTo(File(app.getExternalFilesDir(null), "verification/long-m4b-$start.pcm"), overwrite = true)
            }
        } finally { pcm.delete() }
    }
}
