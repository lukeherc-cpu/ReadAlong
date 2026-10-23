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

/** Identical audio and final transcript hashes; baseline starts three, serial starts the queue. */
class SerialThroughputTest {
    @Test fun equalWorkload() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("throughput") != null)
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        check(app.speechModel.available())
        val original = app.appPreferences.preferences.first()
        val prefix = "throughput-${System.nanoTime()}"
        val ids = (0..2).map { "$prefix-$it" }
        val directory = File(app.cacheDir, "exports").apply { mkdirs() }
        val wave = File(directory, "$prefix.wav")
        val files = mutableListOf<File>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            app.appPreferences.update { it.copy(chargingOnly = false, chargingOverrideUntil = 0, setupStage = 3, batteryWarningSeen = true) }
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-concurrent-short.wav")).use { input -> wave.outputStream().use { input.copyTo(it) } }
            for (id in ids) {
                app.database.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, title = id, isDemo = false, durationMs = 18_000)))
                app.database.mediaDao().putAudio((0..2).map { n ->
                    val copy = File(directory, "$id-$n.wav"); wave.copyTo(copy); files += copy
                    AudioFileEntity("$id-$n", id, androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", copy).toString(), "Verification $n", n, 6_000, null)
                })
                app.processing.imported(id)
            }
            val began = android.os.SystemClock.elapsedRealtime()
            if (args.getString("throughput") == "parallel") ids.forEach { app.processing.transcribeNext(it) }
            else { ids.forEach { app.processing.addToQueue(it) }; app.processing.playQueued(ids.first()) }
            var maxActive = 0
            val finishedAt = mutableMapOf<String, Long>()
            withTimeout(600_000) {
                while (finishedAt.size < ids.size) {
                    val jobs = ids.map { app.database.processingDao().find(it)!! }
                    maxActive = maxOf(maxActive, jobs.count { it.state in setOf("PROCESSING", "PARTIAL_READY", "GENERATING_EPUB") })
                    jobs.forEach { job ->
                        assertNotEquals(job.message, "FAILED", job.state)
                        if (job.state == "READY") finishedAt.putIfAbsent(job.bookId, android.os.SystemClock.elapsedRealtime() - began)
                    }
                    delay(100)
                }
            }
            val hashes = ids.map { id ->
                val job = app.database.processingDao().find(id)!!; assertEquals(3, job.completedUnits)
                val rows = app.database.readingDao().segments(id)
                check(rows.isNotEmpty())
                val normalized = rows.joinToString("\n") { "${it.startMs}|${it.endMs}|${it.text}|${it.wordsJson}" }
                java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
            }
            assertEquals(1, hashes.toSet().size)
            if (args.getString("throughput") != "parallel") assertEquals(1, maxActive)
            val result = org.json.JSONObject().put("mode", args.getString("throughput")).put("audio_ms", 54_000)
                .put("elapsed_ms", finishedAt.values.max()).put("first_book_ms", finishedAt.values.min()).put("max_active", maxActive)
                .put("content_sha256", hashes.first()).put("book_completion_ms", org.json.JSONArray(ids.map { finishedAt[it] }))
            File(app.getExternalFilesDir(null), "verification-throughput.json").writeText(result.toString(2))
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("throughput", result.toString()) })
        } finally {
            ids.forEach { app.processing.cancel(it); app.database.bookDao().removeBook(it) }
            app.appPreferences.update { original }; wave.delete(); files.forEach(File::delete); scenario.close()
        }
    }
}
