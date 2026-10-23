package com.example.readalong.data

import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.MainActivity
import com.example.readalong.data.local.*
import com.example.readalong.data.processing.*
import com.example.readalong.data.transcription.TranscriptionNotifications
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConcurrentTranscriptionTest {
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    @Test fun oneWhistleProcessPriorityPreemptionAndAutomaticQueueDrain() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("whistleModel") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        org.junit.Assume.assumeTrue(app.speechModel.available())
        // A crashed instrumentation run may leave only these synthetic fixture IDs behind.
        app.bookRepository.observeBooks().first().filter { it.id.startsWith("parallel-") }.forEach {
            app.processing.cancel(it.id); app.playback.releaseBook(it.id); app.database.bookDao().removeBook(it.id)
        }
        val original = app.appPreferences.preferences.first()
        val ids = (0..3).map { "parallel-${System.nanoTime()}-$it" }
        val files = mutableListOf<File>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            app.appPreferences.update { it.copy(chargingOnly = false, chargingOverrideUntil = 0, setupStage = 3, batteryWarningSeen = true) }
            ids.forEachIndexed { index, id ->
                val wave = File(File(app.cacheDir, "exports").apply { mkdirs() }, "$id.wav").also { files += it }
                ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-concurrent-short.wav")).use { input -> wave.outputStream().use { input.copyTo(it) } }
                assertTrue(wave.length() > 100_000)
                app.database.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, title = "Concurrent book $index", isDemo = false, durationMs = 6_000)))
                app.database.mediaDao().putAudio(listOf(AudioFileEntity("$id-file", id, androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", wave).toString(), "First chapter", 0, 6_000, null)))
                app.processing.imported(id)
            }
            ids.forEach { app.processing.addToQueue(it) }
            app.processing.playQueued(ids.first())
            withTimeout(30_000) { while (shell("pidof ${app.packageName}:recognition").isBlank()) delay(50) }
            assertTrue(shell("pidof ${app.packageName}:recognition_2").isBlank())
            assertTrue(shell("pidof ${app.packageName}:recognition_3").isBlank())
            app.processing.playQueued(ids[2])
            assertEquals("WAITING_QUEUE", app.database.processingDao().find(ids[0])!!.state)
            assertEquals(ids[2], app.database.processingDao().registeredQueue().first().bookId)
            app.processing.cancel(ids[2])
            assertEquals("CANCELLED", app.database.processingDao().find(ids[2])!!.state)
            app.processing.playQueued(ids[0])
            withTimeout(240_000) { while (listOf(ids[0], ids[1], ids[3]).any { app.database.processingDao().find(it)?.state != "READY" }) {
                assertTrue(app.database.processingDao().unfinished().size <= 1)
                delay(100)
            } }
            assertEquals("CANCELLED", app.database.processingDao().find(ids[2])!!.state)
            listOf(ids[0], ids[1], ids[3]).forEach { assertTrue(app.database.readingDao().segments(it).isNotEmpty()) }
        } finally {
            ids.forEach { app.processing.cancel(it); app.playback.releaseBook(it); app.database.bookDao().removeBook(it) }
            app.appPreferences.update { original }; files.forEach(File::delete); scenario.close()
        }
    }
}
