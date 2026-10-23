package com.example.readalong.data

import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.local.ProcessingJobEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import com.example.readalong.data.processing.chapterTitle
import com.example.readalong.data.processing.notificationLabel

class TranscriptionNotificationTest {
    @Test fun queueOrderStaysFixedAcrossProgressUpdatesAndChangesWithReordering() {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val notifications = com.example.readalong.data.transcription.TranscriptionNotifications
        val values = listOf(Long.MIN_VALUE, -100L, -1L, 0L, 1L, Long.MAX_VALUE)
        assertEquals(values, values.sortedBy { notifications.sortKey(it, "same") })
        notifications.queuePosition("order-a", -2)
        notifications.queuePosition("order-b", -1)
        val a = notifications.build(app, "A", "Chapter · 10% / Full book 1%", "order-a", 1)
        val updated = notifications.build(app, "A", "Chapter · 90% / Full book 80%", "order-a", 80)
        val b = notifications.build(app, "B", "Chapter · 0% / Full book 0%", "order-b", 0)
        assertEquals(a.sortKey, updated.sortKey); assertEquals(a.`when`, updated.`when`)
        assertTrue(a.sortKey < b.sortKey)
        notifications.queuePosition("order-b", -3)
        assertTrue(notifications.build(app, "B", "Chapter · 1% / Full book 1%", "order-b", 1).sortKey < a.sortKey)
    }

    @Test fun progressUsesTheChapterBeingTranscribedAtEachPlanBoundary() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val id = "chapter-label-${System.nanoTime()}"
        try {
            app.database.bookDao().insertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false)))
            app.database.mediaDao().putAudio(listOf(com.example.readalong.data.local.AudioFileEntity("$id-file", id, "file:///$id", "Opening Credits.m4b", 0, 90_000, null)))
            app.database.mediaDao().putChapters(listOf(
                com.example.readalong.data.local.AudioChapterEntity("$id-intro", "$id-file", "Opening Credits", 0, 0, 30_000),
                com.example.readalong.data.local.AudioChapterEntity("$id-woods", "$id-file", "Into the Woods", 1, 30_000, 90_000)))
            val plan = com.example.readalong.data.processing.TranscriptionPlan.encode(listOf(
                com.example.readalong.domain.transcription.TranscriptionInput(id, "$id-intro", null, 0, 30_000, true),
                com.example.readalong.domain.transcription.TranscriptionInput(id, "$id-woods", null, 30_000, 60_000, true),
                com.example.readalong.domain.transcription.TranscriptionInput(id, "$id-woods", null, 60_000, 90_000, true)))
            val job = ProcessingJobEntity(id, UUID.randomUUID().toString(), "fixture", "PROCESSING", 0, 3, null, System.currentTimeMillis(), planJson = plan)
            assertEquals("Opening Credits", job.chapterTitle(app.database))
            assertEquals("Into the Woods", job.copy(completedUnits = 1).chapterTitle(app.database))
            assertTrue(job.copy(completedUnits = 2).notificationLabel(app.database).startsWith("Into the Woods · 50%"))
            assertEquals("Into the Woods", job.copy(completedUnits = 3, state = "READY").chapterTitle(app.database))
        } finally { app.database.bookDao().removeBook(id) }
    }

    @Test fun compactNotificationInflatesAndItsPausePreservesCheckpoint() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        val id = "notification-view-${System.nanoTime()}"
        try {
            app.database.bookDao().insertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false)))
            app.database.processingDao().put(ProcessingJobEntity(id, UUID.randomUUID().toString(), "fixture", "QUEUED", 4, 100, "Saved text.", System.currentTimeMillis()))
            val notification = com.example.readalong.data.transcription.TranscriptionNotifications.build(app, "A long audiobook title", "Ch 2 :17% / Full book 8%", id, 8)
            if (InstrumentationRegistry.getArguments().getString("visualNotification") == "true") app.getSystemService(NotificationManager::class.java).notify("layout-preview", 5600, notification)
            else app.getSystemService(NotificationManager::class.java).cancel("layout-preview", 5600)
            withContext(Dispatchers.Main) {
                val parent = android.widget.FrameLayout(app)
                val view = notification.contentView.apply(app, parent)
                val expanded = notification.bigContentView.apply(app, parent)
                assertEquals("Chapter · 17% complete", expanded.findViewById<android.widget.TextView>(com.example.readalong.R.id.transcription_chapter_progress).text.toString())
                assertEquals("Full book · 8% complete", expanded.findViewById<android.widget.TextView>(com.example.readalong.R.id.transcription_book).text.toString())
                assertTrue(expanded.findViewById<android.widget.TextView>(com.example.readalong.R.id.transcription_chapter).maxLines > 1)
                assertEquals("A long audiobook title", view.findViewById<android.widget.TextView>(com.example.readalong.R.id.transcription_title).text.toString())
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(image))
                java.io.File(app.getExternalFilesDir(null), "verification-transcription-notification.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                view.findViewById<android.widget.ImageButton>(com.example.readalong.R.id.transcription_pause).performClick()
            }
            withTimeout(5000) { while (app.database.processingDao().find(id)?.state != "CANCELLED") delay(50) }
            assertEquals(4, app.database.processingDao().find(id)!!.completedUnits)
            val manager = app.getSystemService(NotificationManager::class.java)
            withTimeout(10_000) {
                while (manager.activeNotifications.none { it.id == com.example.readalong.data.transcription.TranscriptionNotifications.notificationId(id) && it.notification.extras.getString(android.app.Notification.EXTRA_SUB_TEXT)?.startsWith("Paused") == true }) delay(100)
            }
            val paused = manager.activeNotifications.first { it.id == com.example.readalong.data.transcription.TranscriptionNotifications.notificationId(id) }.notification
            assertTrue(paused.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
            withContext(Dispatchers.Main) {
                val view = paused.contentView.apply(app, android.widget.FrameLayout(app))
                assertEquals("Resume transcription", view.findViewById<android.widget.ImageButton>(com.example.readalong.R.id.transcription_pause).contentDescription)
            }
            // System swipe delivers deleteIntent. Clearing the posted card alone must not stop work.
            paused.deleteIntent.send()
            withTimeout(5000) { while (!com.example.readalong.data.transcription.TranscriptionNotifications.dismissed(app, id)) delay(20) }
            manager.cancel(com.example.readalong.data.transcription.TranscriptionNotifications.notificationId(id))
            val saved = app.database.processingDao().find(id)!!
            app.database.processingDao().put(saved.copy(message = "A later checkpoint update"))
            delay(2500)
            assertTrue("Dismissal must survive both Room emissions and the periodic update", manager.activeNotifications.none { it.id == com.example.readalong.data.transcription.TranscriptionNotifications.notificationId(id) })
            assertEquals(4, app.database.processingDao().find(id)!!.completedUnits)
            com.example.readalong.data.transcription.TranscriptionNotifications.showForExplicitStart(app, id)
            app.database.processingDao().put(saved.copy(message = "Explicit resume can show progress again"))
            withTimeout(5000) { while (manager.activeNotifications.none { it.id == com.example.readalong.data.transcription.TranscriptionNotifications.notificationId(id) }) delay(50) }

        } finally { app.database.bookDao().removeBook(id) }
    }

    @Test fun foregroundPauseKeepsItsNotificationAndPlayResumesTheSavedPlan() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("whistleModel") == "true")
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        org.junit.Assume.assumeTrue(app.speechModel.available())
        val original = app.appPreferences.preferences.first()
        val id = "notification-foreground-${System.nanoTime()}"
        val wave = java.io.File(java.io.File(app.cacheDir, "exports").apply { mkdirs() }, "$id.wav")
        val scenario = androidx.test.core.app.ActivityScenario.launch(com.example.readalong.MainActivity::class.java)
        val manager = app.getSystemService(NotificationManager::class.java)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-natural.wav")).use { input -> wave.outputStream().use { input.copyTo(it) } }
            app.appPreferences.update { it.copy(chargingOnly = false, chargingOverrideUntil = 0) }
            app.database.bookDao().insertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, title = "Pause and resume fixture", isDemo = false, durationMs = 30_000)))
            app.database.mediaDao().putAudio(listOf(com.example.readalong.data.local.AudioFileEntity("$id-file", id, androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", wave).toString(), "Notification fixture", 0, 30_000, null)))
            app.processing.prepare(id)
            println("Foreground notification stage: requested work ${app.database.processingDao().find(id)?.state}")
            withTimeout(10_000) { while (manager.activeNotifications.none { it.id == com.example.readalong.data.transcription.TranscriptionNotifications.notificationId(id) && it.notification.extras.getString(android.app.Notification.EXTRA_TITLE) == "Pause and resume fixture" }) delay(50) }
            println("Foreground notification stage: running notification received")
            val running = manager.activeNotifications.first { it.id == com.example.readalong.data.transcription.TranscriptionNotifications.notificationId(id) }.notification
            withContext(Dispatchers.Main) { running.contentView.apply(app, android.widget.FrameLayout(app)).findViewById<android.widget.ImageButton>(com.example.readalong.R.id.transcription_pause).performClick() }
            withTimeout(10_000) { while (app.database.processingDao().find(id)?.state != "CANCELLED") delay(50) }
            println("Foreground notification stage: pause saved")
            val checkpoint = app.database.processingDao().find(id)!!
            withTimeout(10_000) { while (manager.activeNotifications.none { it.id == com.example.readalong.data.transcription.TranscriptionNotifications.notificationId(id) && it.notification.extras.getString(android.app.Notification.EXTRA_SUB_TEXT)?.startsWith("Paused") == true }) delay(50) }
            println("Foreground notification stage: paused notification received")
            delay(400)
            val paused = manager.activeNotifications.first { it.id == com.example.readalong.data.transcription.TranscriptionNotifications.notificationId(id) }.notification
            assertTrue(paused.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(checkpoint.completedUnits, app.database.processingDao().find(id)!!.completedUnits)
            withContext(Dispatchers.Main) { paused.contentView.apply(app, android.widget.FrameLayout(app)).findViewById<android.widget.ImageButton>(com.example.readalong.R.id.transcription_pause).performClick() }
            withTimeout(180_000) { app.database.processingDao().observe(id).first { it?.state in listOf("READY", "FAILED") } }
            val complete = app.database.processingDao().find(id)!!
            assertEquals(complete.message, "READY", complete.state)
            assertEquals(1, complete.completedUnits)
            assertFalse(app.playback.state.value.isPlaying)
        } finally {
            app.processing.cancel(id); app.playback.releaseBook(id); app.database.bookDao().removeBook(id)
            app.appPreferences.update { original }; wave.delete(); scenario.close()
        }
    }

    @Test fun failedWorkOffersResumeButExplicitPauseDoesNotAlert() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        val manager = app.getSystemService(NotificationManager::class.java)
        val failed = "notify-failure-${System.nanoTime()}"
        val paused = "notify-pause-${System.nanoTime()}"
        try {
            app.database.bookDao().insertBooks(listOf(failed, paused).map { com.example.readalong.data.book.DemoBooks.books.first().copy(id = it, isDemo = false) })
            val work = UUID.randomUUID().toString()
            app.database.processingDao().put(ProcessingJobEntity(failed, work, "fixture", "FAILED", 4, 100, "Injected test interruption. Completed text is saved.", System.currentTimeMillis()))
            app.database.processingDao().put(ProcessingJobEntity(paused, UUID.randomUUID().toString(), "fixture", "CANCELLED", 4, 100, "Preparation paused.", System.currentTimeMillis()))
            withTimeout(10_000) { while (manager.activeNotifications.none { it.tag == "stopped:$failed" }) delay(100) }
            val notification = manager.activeNotifications.first { it.tag == "stopped:$failed" }.notification
            assertEquals("Retry", notification.actions.single().title.toString())
            assertEquals(app.packageName, notification.actions.single().actionIntent.creatorPackage)
            assertEquals(0, notification.flags and android.app.Notification.FLAG_ONGOING_EVENT)
            assertFalse(manager.activeNotifications.any { it.tag == "stopped:$paused" })
            assertEquals(4, app.database.processingDao().find(failed)!!.completedUnits)
            app.database.processingDao().put(app.database.processingDao().find(failed)!!.copy(state = "READY"))
            withTimeout(10_000) { while (manager.activeNotifications.any { it.tag == "stopped:$failed" }) delay(100) }
        } finally {
            listOf(failed, paused).forEach { app.database.bookDao().removeBook(it); manager.cancel("stopped:$it", 4302) }
        }
    }
}
