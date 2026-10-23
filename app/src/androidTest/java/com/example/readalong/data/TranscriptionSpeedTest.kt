package com.example.readalong.data

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.MainActivity
import com.example.readalong.data.local.AudioFileEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Same six bounded chapters on both APKs; excludes download, installs and Gradle work. */
class TranscriptionSpeedTest {
    @Test fun sixChaptersKeepTheirTextTimingAndCheckpointAcrossBatches() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("speedBenchmark") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        org.junit.Assume.assumeTrue(app.speechModel.available())
        val original = app.appPreferences.preferences.first()
        val id = "speed-${System.nanoTime()}"
        val wave = File(File(app.cacheDir, "exports").apply { mkdirs() }, "$id.wav")
        val copies = (0..5).map { File(wave.parentFile, "$id-$it.wav") }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            app.appPreferences.update { it.copy(chargingOnly = false, setupStage = 3, batteryWarningSeen = true) }
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-concurrent-short.wav")).use { input -> wave.outputStream().use { input.copyTo(it) } }
            check(wave.length() > 100_000)
            app.database.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, title = "Speed verification", isDemo = false, durationMs = 36_000)))
            copies.forEach { wave.copyTo(it) }
            app.database.mediaDao().putAudio((0..5).map { AudioFileEntity("$id-$it", id, androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", copies[it]).toString(), "Verification $it", it, 6_000, null) })
            app.processing.imported(id)
            val began = android.os.SystemClock.elapsedRealtime()
            app.processing.transcribeNext(id)
            val ready = withTimeout(600_000) { app.processing.observe(id).first { it?.state in setOf("READY", "FAILED") } }!!
            val total = android.os.SystemClock.elapsedRealtime() - began
            assertEquals(ready.message, "READY", ready.state)
            assertEquals(6, ready.completedUnits)
            val rows = app.database.readingDao().segments(id)
            assertTrue(rows.isNotEmpty())
            val normalized = rows.joinToString("\n") { "${it.startMs}|${it.endMs}|${it.text}|${it.wordsJson}" }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
            val result = org.json.JSONObject().put("elapsed_ms", total).put("segments", rows.size).put("content_sha256", hash)
            File(app.getExternalFilesDir(null), "verification-speed.json").writeText(result.toString(2))
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("speed", result.toString()) })
        } finally {
            app.processing.cancel(id); app.playback.releaseBook(id); app.database.bookDao().removeBook(id)
            app.appPreferences.update { original }; wave.delete(); copies.forEach(File::delete); scenario.close()
        }
    }
}
