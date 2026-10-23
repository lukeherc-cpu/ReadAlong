package com.example.readalong.data

import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.local.*
import com.example.readalong.data.processing.*
import com.example.readalong.data.transcription.SpeechModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TranscriptionPowerTest {
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { String(it.readBytes()) }
    @Test fun unpluggedRequestWaitsWithoutRecognitionThenPowerResumesAndSaves() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("whistleModel") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        assumeTrue(app.speechModel.available())
        val original = app.appPreferences.preferences.first()
        val id = "power-fixture-${System.nanoTime()}"
        val wave = File(File(app.cacheDir, "exports").apply { mkdirs() }, "$id.wav")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-natural.wav")).use { input -> wave.outputStream().use { input.copyTo(it) } }
            assertTrue(wave.length() > 1000)
            shell("dumpsys battery unplug")
            app.appPreferences.update { it.copy(chargingOnly = true, chargingOverrideUntil = 0) }
            withTimeout(5_000) { while (!ChargingPolicy.shouldWait(app)) delay(50) }
            assertTrue(ChargingPolicy.shouldWait(app))
            app.database.bookDao().insertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false, durationMs = 30_000)))
            app.database.mediaDao().putAudio(listOf(AudioFileEntity("$id-file", id, FileProvider.getUriForFile(app, "${app.packageName}.files", wave).toString(), "Power fixture", 0, 30_000, null)))
            app.processing.prepare(id)
            val pending = app.database.processingDao().find(id)!!
            assertEquals(0, pending.completedUnits)
            assertTrue(pending.message!!.startsWith("Waiting for charging"))
            assertTrue(app.processing.request(id).workSpec.constraints.requiresCharging())
            assertFalse(AudiobookTranscriptionService.running(id, pending.workId))
            delay(1500)
            assertEquals(0, app.database.processingDao().find(id)!!.completedUnits)
            shell("dumpsys battery set ac 1")
            shell("dumpsys battery set status 2")
            withTimeout(5_000) { while (ChargingPolicy.shouldWait(app)) delay(50) }
            assertFalse(ChargingPolicy.shouldWait(app))
            withTimeout(180_000) { app.database.processingDao().observe(id).first { it?.state in listOf("READY", "FAILED") } }
            val completed = app.database.processingDao().find(id)!!
            assertEquals(completed.message, "READY", completed.state)
            assertEquals(1, completed.completedUnits)
            assertTrue(app.database.readingDao().segments(id).isNotEmpty())
        } finally {
            shell("dumpsys battery reset")
            app.appPreferences.update { original }
            app.processing.cancel(id)
            WorkManager.getInstance(app).cancelUniqueWork("readable:$id").result.get()
            app.database.bookDao().removeBook(id)
            wave.delete()
        }
    }

    @Test fun oneActiveBookAndWaitingQueueResumeOnProtectedPower() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("whistleModel") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        assumeTrue(app.speechModel.available())
        val original = app.appPreferences.preferences.first()
        val ids = (0..2).map { "power-three-${System.nanoTime()}-$it" }
        val files = mutableListOf<File>()
        val scenario = androidx.test.core.app.ActivityScenario.launch(com.example.readalong.MainActivity::class.java)
        try {
            shell("dumpsys battery reset")
            app.appPreferences.update { it.copy(chargingOnly = false, chargingOverrideUntil = 0, setupStage = 3, batteryWarningSeen = true) }
            for (id in ids) {
                app.database.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false, durationMs = 18_000)))
                val audio = (0..2).map { index ->
                    val file = File(File(app.cacheDir, "exports").apply { mkdirs() }, "$id-$index.wav").also { files += it }
                    ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-concurrent-short.wav")).use { input -> file.outputStream().use { input.copyTo(it) } }
                    AudioFileEntity("$id-$index", id, FileProvider.getUriForFile(app, "${app.packageName}.files", file).toString(), "Named chapter $index", index, 6_000, null)
                }
                app.database.mediaDao().putAudio(audio); app.processing.imported(id); app.processing.addToQueue(id)
            }
            app.processing.playQueued(ids.first())
            withTimeout(120_000) { while (ids.none { (app.database.processingDao().find(it)?.completedUnits ?: 0) > 0 }) delay(50) }
            shell("dumpsys battery unplug")
            app.appPreferences.update { it.copy(chargingOnly = true, chargingOverrideUntil = 0) }
            app.processing.chargingPreferenceChanged()
            withTimeout(15_000) { while (app.database.processingDao().find(ids.first())?.message?.startsWith("Waiting for charging") != true) delay(50) }
            val waiting = ids.map { app.database.processingDao().find(it)!! }
            assertEquals("QUEUED", waiting.first().state)
            assertTrue(waiting.drop(1).all { it.state == "WAITING_QUEUE" })
            assertTrue(waiting.sumOf { it.completedUnits } >= 1)
            val positions = waiting.map { it.queuePosition }
            delay(1000)
            // Battery protection may report NOT_CHARGING despite an attached power source.
            shell("dumpsys battery set ac 1"); shell("dumpsys battery set status 4")
            withTimeout(5_000) { while (ChargingPolicy.shouldWait(app)) delay(50) }
            assertFalse(ChargingPolicy.shouldWait(app))
            assertFalse(app.processing.request(ids.first()).workSpec.constraints.requiresCharging())
            withTimeout(180_000) { while (ids.any { app.database.processingDao().find(it)?.state !in setOf("READY", "FAILED") }) delay(100) }
            ids.forEachIndexed { index, id ->
                val done = app.database.processingDao().find(id)!!
                assertEquals(done.message, "READY", done.state); assertEquals(3, done.completedUnits)
                if (index == 0) assertEquals(waiting[index].planJson, done.planJson); assertEquals(positions[index], done.queuePosition)
            }
        } finally {
            shell("dumpsys battery reset"); app.appPreferences.update { original }
            ids.forEach { app.processing.cancel(it); app.playback.releaseBook(it); app.database.bookDao().removeBook(it) }
            files.forEach(File::delete); scenario.close()
        }
    }

    @Test fun thirtyMinuteOverridePersistsAndExpiresWithoutChangingChargingPreference() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val original = app.appPreferences.preferences.first()
        try {
            shell("dumpsys battery unplug")
            app.appPreferences.update { it.copy(chargingOnly = true, chargingOverrideUntil = 0) }
            assertTrue(ChargingPolicy.shouldWait(app))
            val before = System.currentTimeMillis()
            app.processing.allowBatteryForThirtyMinutes()
            val saved = com.example.readalong.data.preferences.AppPreferencesStore(app).preferences.first()
            assertTrue(saved.chargingOnly)
            assertTrue(saved.chargingOverrideUntil in before + 30 * 60_000L .. System.currentTimeMillis() + 30 * 60_000L)
            assertFalse(ChargingPolicy.shouldWait(app))
            assertFalse(app.processing.request("override-test").workSpec.constraints.requiresCharging())
            assertTrue(ChargingPolicy.restricted(saved, saved.chargingOverrideUntil))
            app.appPreferences.update { it.copy(chargingOverrideUntil = before - 1) }
            assertTrue(ChargingPolicy.shouldWait(app))
            assertTrue(app.processing.request("override-test").workSpec.constraints.requiresCharging())
        } finally { shell("dumpsys battery reset"); app.appPreferences.update { original } }
    }

    @Test fun estimateSurvivesPauseAndRestartButRejectsADifferentAudioPlan() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        try {
            val units = (0 until 10).map { com.example.readalong.domain.transcription.TranscriptionInput("eta", "chapter", "local", it * 30_000L, (it + 1) * 30_000L, false) }
            val job = ProcessingJobEntity("eta", "one", SpeechModelStore.ID, "PROCESSING", 2, 10, null, 0, TranscriptionPlan.encode(units), "same-audio")
            assertTrue(TranscriptionEstimate.label(db, job).startsWith("Estimating"))
            TranscriptionEstimate.record(db, job, 30_000, 15_000)
            TranscriptionEstimate.record(db, job, 30_000, 15_000)
            val expected = TranscriptionEstimate.label(db, job)
            assertEquals("About 3 min remaining", expected)
            assertEquals(expected, TranscriptionEstimate.label(db, job.copy(state = "QUEUED", workId = "after-restart", updatedAt = Long.MAX_VALUE)))
            assertTrue(TranscriptionEstimate.label(db, job.copy(sourceFingerprint = "different-audio")).startsWith("Estimating"))
        } finally { db.close() }
    }
}
