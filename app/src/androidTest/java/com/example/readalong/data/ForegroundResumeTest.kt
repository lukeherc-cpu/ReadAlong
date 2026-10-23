package com.example.readalong.data

import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.MainActivity
import com.example.readalong.data.local.*
import com.example.readalong.data.processing.AudiobookTranscriptionService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ForegroundResumeTest {
    @Test fun serviceStopAutomaticallyResumesSavedCheckpointWithRealWhistle() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("whistleModel") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        assumeTrue(app.speechModel.available())
        val original = app.appPreferences.preferences.first()
        val id = "foreground-resume-${System.nanoTime()}"
        val files = mutableListOf<File>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            app.appPreferences.update { it.copy(chargingOnly = false, setupStage = 3, batteryWarningSeen = true) }
            app.database.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false, durationMs = 24_000)))
            val audio = (0..3).map { i ->
                val file = File(File(app.cacheDir, "exports").apply { mkdirs() }, "$id-$i.wav").also { files += it }
                ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-concurrent-short.wav")).use { input -> file.outputStream().use { input.copyTo(it) } }
                AudioFileEntity("$id-$i", id, FileProvider.getUriForFile(app, "${app.packageName}.files", file).toString(), "Resume chapter $i", i, 6_000, null)
            }
            app.database.mediaDao().putAudio(audio)
            app.processing.imported(id); app.processing.transcribeNext(id)
            val checkpoint = withTimeout(120_000) { app.database.processingDao().observe(id).first { (it?.completedUnits ?: 0) >= 1 && it?.state == "PROCESSING" } }!!
            app.stopService(android.content.Intent(app, AudiobookTranscriptionService::class.java))
            val resumed = withTimeout(30_000) { app.database.processingDao().observe(id).first { it != null && it.workId != checkpoint.workId } }!!
            assertTrue(resumed.completedUnits >= checkpoint.completedUnits)
            assertEquals(checkpoint.planJson, resumed.planJson)
            assertEquals(checkpoint.queuePosition, resumed.queuePosition)
            val finished = withTimeout(180_000) { app.database.processingDao().observe(id).first { it?.state in listOf("READY", "FAILED") } }!!
            assertEquals(finished.message, "READY", finished.state)
            assertEquals(4, finished.completedUnits)
            assertTrue(app.database.readingDao().count(id) > 0)
        } finally {
            app.processing.cancel(id)
            com.example.readalong.data.book.BookActionsRepository(app, app.database).delete(id)
            files.forEach { it.delete() }
            app.appPreferences.update { original }; scenario.close()
        }
    }
}
