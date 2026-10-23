package com.example.readalong.data

import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.local.*
import com.example.readalong.data.processing.*
import com.example.readalong.data.transcription.TranscriptionNotifications
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class BackgroundTranscriptionTest {
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { String(it.readBytes()) }

    @Test fun recoveryHandsTheSameCheckpointToForegroundAndRejectsPausedOrStaleCommands() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val id = "handoff-${System.nanoTime()}"
        val starts = mutableListOf<android.content.Intent>()
        val repo = ProcessingRepository(app, db, app.speechModel, launchPreparation = { starts += it })
        val preferences = app.appPreferences.preferences.first()
        try {
            app.appPreferences.update { it.copy(chargingOnly = false) }
            db.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false)))
            val plan = "saved-plan";val job = ProcessingJobEntity(id, java.util.UUID.randomUUID().toString(), "whistle", "QUEUED", 7, 30, null, 0, plan, "fingerprint")
            db.processingDao().put(job)
            assertTrue(repo.handoffToForeground(id, job.workId))
            assertEquals(job.workId, starts.single().getStringExtra("workId"))
            assertEquals(AudiobookTranscriptionService::class.java.name, starts.single().component!!.className)
            assertEquals(job, db.processingDao().find(id))
            assertFalse(repo.handoffToForeground(id, "stale"))
            db.processingDao().put(job.copy(state = "CANCELLED"))
            assertFalse(repo.handoffToForeground(id, job.workId))
            assertEquals(1, starts.size)
        } finally {
            WorkManager.getInstance(app).cancelUniqueWork("readable-recovery:$id").result.get()
            app.appPreferences.update { preferences };db.close()
        }
    }

    @Test fun chargingRecoveryAndNextBookStayForegroundWithoutReopeningTheActivity() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("whistleModel") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        assumeTrue(app.speechModel.available())
        assumeTrue(app.database.processingDao().unfinished().isEmpty())
        val before = app.appPreferences.preferences.first()
        val exempt = app.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
        val oldQueue = app.database.bookDao().metadata("transcription_queue_running")
        val ids = (0..1).map { "background-power-${System.nanoTime()}-$it" }
        val wave = File(File(app.filesDir, "covers").apply { mkdirs() }, "${ids.first()}.wav")
        val scenario = ActivityScenario.launch(com.example.readalong.MainActivity::class.java)
        try {
            app.appPreferences.update { it.copy(chargingOnly = true, chargingOverrideUntil = 0, setupStage = 3, batteryWarningSeen = true) }
            shell("dumpsys deviceidle whitelist +${app.packageName}");shell("dumpsys battery unplug")
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-concurrent-short.wav")).use { input -> wave.outputStream().use { input.copyTo(it) } }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", wave).toString()
            ids.forEach { id ->
                app.database.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false, durationMs = 36_000)))
                app.database.mediaDao().putAudio((0..5).map { AudioFileEntity("$id-$it", id, "$uri?book=$id&track=$it", "Named section $it", it, 6000, null) })
                app.processing.imported(id);app.processing.addToQueue(id)
            }
            app.processing.playQueued(ids.first())
            val waiting = app.database.processingDao().find(ids.first())!!
            assertTrue(waiting.message!!.startsWith("Waiting for charging"))
            shell("input keyevent KEYCODE_HOME");shell("input keyevent KEYCODE_SLEEP")
            shell("dumpsys battery set ac 1");shell("dumpsys battery set status 2")
            val sawForeground = mutableSetOf<String>()
            withTimeout(180_000) {
                while (true) {
                    val jobs = ids.map { app.database.processingDao().find(it)!! }
                    jobs.forEach { job ->
                        if (AudiobookTranscriptionService.running(job.bookId, job.workId)) sawForeground += job.bookId
                        assertNotEquals(job.message, "FAILED", job.state)
                    }
                    if (jobs.all { it.state == "READY" }) break
                    delay(50)
                }
            }
            assertEquals(ids.toSet(), sawForeground)
            ids.forEach { assertEquals(6, app.database.processingDao().find(it)!!.completedUnits) }
            assertEquals(waiting.planJson, app.database.processingDao().find(ids.first())!!.planJson)
            assertFalse(app.getSystemService(android.os.PowerManager::class.java).isInteractive)
        } finally {
            ids.forEach { app.processing.cancel(it) }
            withTimeoutOrNull(15_000) { while (AudiobookTranscriptionService.activeBook in ids) delay(50) }
            ids.forEach { app.database.bookDao().removeBook(it) };wave.delete()
            shell("dumpsys battery reset");shell("input keyevent KEYCODE_WAKEUP")
            if (!exempt) shell("dumpsys deviceidle whitelist -${app.packageName}")
            app.appPreferences.update { before }
            if (oldQueue != null) app.database.bookDao().putMetadata(LibraryMetadataEntity("transcription_queue_running", oldQueue))
            else app.database.bookDao().removeMetadata("transcription_queue_running")
            scenario.close()
        }
    }

    /** Real Whistle, screen off for >6 minutes, then Resume from the actual notification action.
     * Opt-in: no user books or queue may be active. Battery exemption is restored on exit. */
    @Test fun screenOffForSixMinutesThenNotificationResumeRetainsForegroundAndCheckpoint() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("backgroundSoak") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        assumeTrue(app.speechModel.available())
        assumeTrue(app.database.processingDao().unfinished().isEmpty())
        val before = app.appPreferences.preferences.first()
        val wasExempt = app.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
        val previousQueue = app.database.bookDao().metadata("transcription_queue_running")
        val id = "background-soak-${System.nanoTime()}"
        val wave = File(File(app.filesDir, "covers").apply { mkdirs() }, "$id.wav")
        val report = File(app.getExternalFilesDir(null), "background-transcription-soak.tsv")
        val scenario = ActivityScenario.launch(com.example.readalong.MainActivity::class.java)
        try {
            app.appPreferences.update { it.copy(chargingOnly = false, setupStage = 3, batteryWarningSeen = true, homeGuideHidden = true) }
            shell("dumpsys deviceidle whitelist +${app.packageName}")
            shell("dumpsys battery unplug")
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-concurrent-short.wav")).use { input -> wave.outputStream().use { input.copyTo(it) } }
            assertTrue(wave.length() > 1000)
            app.database.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false, durationMs = 6_000_000)))
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", wave).toString()
            app.database.mediaDao().putAudio((0 until 1000).map { AudioFileEntity("$id-$it", id, "$uri?fixture=$it", "Speech fixture $it", it, 6000, null) })
            assertEquals(1000, app.database.mediaDao().audioFiles(id).size)
            app.processing.transcribeNext(id)
            withTimeout(120_000) { while ((app.database.processingDao().find(id)?.completedUnits ?: 0) < 2) delay(100) }
            val start = app.database.processingDao().find(id)!!
            assertTrue(AudiobookTranscriptionService.running(id, start.workId))
            report.writeText("elapsed_s\tcompleted_units\tstate\tforeground\tscreen_interactive\n")
            shell("input keyevent KEYCODE_HOME");shell("input keyevent KEYCODE_SLEEP")
            // Force deep idle with the same background permission offered in app settings.
            shell("dumpsys deviceidle force-idle")
            val began = SystemClock.elapsedRealtime()
            var afterFiveMinutes = start.completedUnits
            repeat(26) { tick ->
                delay(15_000)
                val job = app.database.processingDao().find(id)!!
                val active = AudiobookTranscriptionService.running(id, job.workId)
                val interactive = app.getSystemService(android.os.PowerManager::class.java).isInteractive
                report.appendText("${(SystemClock.elapsedRealtime()-began)/1000}\t${job.completedUnits}\t${job.state}\t$active\t$interactive\n")
                assertNotEquals(job.message, "FAILED", job.state)
                assertTrue("Foreground owner must survive screen-off", active)
                assertFalse(interactive)
                if (tick == 19) afterFiveMinutes = job.completedUnits
            }
            val progressed = app.database.processingDao().find(id)!!
            assertTrue("Must continue committing beyond the reported five-minute failure point", progressed.completedUnits > afterFiveMinutes)
            assertTrue(progressed.completedUnits > start.completedUnits)
            app.processing.cancel(id)
            withTimeout(15_000) { while (AudiobookTranscriptionService.running(id, progressed.workId)) delay(50) }
            val paused = app.database.processingDao().find(id)!!
            val manager = app.getSystemService(NotificationManager::class.java)
            withTimeout(15_000) { while (manager.activeNotifications.none { it.id == TranscriptionNotifications.notificationId(id) && it.notification.extras.getString(android.app.Notification.EXTRA_SUB_TEXT)?.startsWith("Paused") == true }) delay(100) }
            val notification = manager.activeNotifications.first { it.id == TranscriptionNotifications.notificationId(id) }.notification
            withContext(Dispatchers.Main) {
                val view = notification.contentView.apply(app, android.widget.FrameLayout(app))
                assertEquals("Resume transcription", view.findViewById<android.widget.ImageButton>(com.example.readalong.R.id.transcription_pause).contentDescription)
                view.findViewById<android.widget.ImageButton>(com.example.readalong.R.id.transcription_pause).performClick()
            }
            withTimeout(120_000) { while ((app.database.processingDao().find(id)?.completedUnits ?: 0) <= paused.completedUnits) delay(100) }
            val resumed = app.database.processingDao().find(id)!!
            assertTrue(AudiobookTranscriptionService.running(id, resumed.workId))
            assertEquals(paused.planJson, resumed.planJson)
            assertFalse(app.getSystemService(android.os.PowerManager::class.java).isInteractive)
            report.appendText("notification_resume\t${resumed.completedUnits}\t${resumed.state}\ttrue\tfalse\n")
        } finally {
            shell("dumpsys deviceidle unforce");shell("dumpsys battery reset");shell("input keyevent KEYCODE_WAKEUP")
            app.processing.cancel(id)
            withTimeoutOrNull(15_000) { while (AudiobookTranscriptionService.activeBook == id) delay(50) }
            app.database.bookDao().removeBook(id);wave.delete()
            if (!wasExempt) shell("dumpsys deviceidle whitelist -${app.packageName}")
            app.appPreferences.update { before }
            if (previousQueue != null) app.database.bookDao().putMetadata(LibraryMetadataEntity("transcription_queue_running", previousQueue))
            else app.database.bookDao().removeMetadata("transcription_queue_running")
            scenario.close()
        }
    }
}
