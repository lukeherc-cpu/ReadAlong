package com.example.readalong

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Collections

/**
 * The full-screen reader must keep scrolling while a ReadAlong is being made and matched to an EPUB:
 * transcript chunks, processing-job updates and EPUB alignment publish into the database the whole time.
 */
class LiveSyncScrollTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ReadAlongApplication
    private val id = "demo-night-orchard"

    @org.junit.Before fun setUp() = runBlocking {
        app.speechModel.dismissSetup()
        app.appPreferences.update { com.example.readalong.data.preferences.AppPreferences(batteryWarningSeen = true, homeGuideHidden = true, playerGuideHidden = true, readerGuideHidden = true) }
        app.database.bookDao().putMetadata(com.example.readalong.data.local.LibraryMetadataEntity("battery_prompt_v1", "test"))
        app.bookRepository.observeBooks().first()
        app.database.bookDao().insertBooks(com.example.readalong.data.book.DemoBooks.books)
        app.database.bookDao().insertProgress(com.example.readalong.data.book.DemoBooks.progress)
        app.transcriptRepository.prepareDemo(id)
        Unit
    }

    @org.junit.After fun tearDown() = runBlocking {
        app.database.processingDao().find(id)?.let { app.database.processingDao().put(it.copy(state = "READY", message = null)) }
        app.database.bookDao().removeSamples()
        if (app.database.bookDao().metadata("current_book")?.startsWith("demo-") == true) app.database.bookDao().removeMetadata("current_book")
        Unit
    }

    @Test fun epubReaderScrollsWhileTranscriptAndMatchingPublish() = scrollsWhilePublishing(epub = true)
    @Test fun readAlongReaderScrollsWhileTranscriptPublishes() = scrollsWhilePublishing(epub = false)

    private fun scrollsWhilePublishing(epub: Boolean) {
        val rows = runBlocking { app.database.readingDao().segments(id) }
        if (epub) importEpub(rows)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library_grid").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:$id"))
        compose.onNodeWithTag("book:$id").performClick()
        compose.waitUntil(5000) { app.playback.state.value.bookId == id }
        compose.runOnUiThread { app.playback.pause(); app.playback.seek(rows.first().startMs) }
        compose.onNodeWithTag("flip_cover", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("follow_card", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Read in full screen").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader_paragraph").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        Thread.sleep(1000)

        val frames = Collections.synchronizedList(mutableListOf<Double>())
        val metricsThread = HandlerThread("live-sync-frames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0) }
        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val publishing = background.launch { publishLikeAReadAlongBeingMade(epub) }
        val log = StringBuilder()
        try {
            Thread.sleep(1500) // Let a few publications land before touching the page.
            compose.runOnUiThread { compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper)) }
            repeat(3) { attempt ->
                val before = firstParagraph()
                compose.onNodeWithTag("follow_passages").performTouchInput { swipeUp(startY = height * .8f, endY = height * .35f, durationMillis = 450) }
                Thread.sleep(700)
                compose.waitForIdle()
                val after = before?.let { paragraphTop(it.first) }
                log.append("swipe $attempt: '${before?.first?.take(40)}' top ${before?.second} -> $after\n")
                assertNotNull("A paragraph is on screen before swiping", before)
                assertTrue("Swipe $attempt must move the page while the ReadAlong is being made (top ${before!!.second} -> $after)",
                    after == null || before.second - after > 100f)
            }
            val before = firstParagraph()!!
            compose.onNodeWithTag("follow_passages").performTouchInput { swipeDown(startY = height * .3f, endY = height * .75f, durationMillis = 450) }
            Thread.sleep(700); compose.waitForIdle()
            val after = paragraphTop(before.first)
            log.append("swipe down: '${before.first.take(40)}' top ${before.second} -> $after\n")
            assertTrue("Swiping down scrolls back (top ${before.second} -> $after)", after == null || after - before.second > 100f)
        } finally {
            compose.runOnUiThread { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
            metricsThread.quitSafely()
            runBlocking { publishing.cancelAndJoin() }; background.cancel()
            val sorted = synchronized(frames) { frames.toList().sorted() }
            if (sorted.isNotEmpty()) log.append("frames ${sorted.size}, p50 ${sorted[sorted.size / 2]} ms, p95 ${sorted[(sorted.size * .95).toInt().coerceAtMost(sorted.lastIndex)]} ms, max ${sorted.last()} ms, over 50 ms ${sorted.count { it > 50 }}\n")
            val name = if (epub) "epub" else "readalong"
            java.io.File(app.getExternalFilesDir(null), "verification-live-sync-$name.txt").writeText(log.toString())
            runCatching { compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap() }.getOrNull()?.let { bitmap ->
                java.io.File(app.getExternalFilesDir(null), "verification-live-sync-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            android.util.Log.i("LiveSyncScrollTest", log.toString())
            compose.runOnUiThread { app.playback.pause() }
        }
    }

    /** What a ReadAlong being made writes: a job update and a transcript chunk every few hundred ms, and EPUB matching. */
    private suspend fun publishLikeAReadAlongBeingMade(epub: Boolean) {
        val dao = app.database.readingDao()
        val raw = dao.segments(id)
        val total = 400
        app.database.processingDao().put(com.example.readalong.data.local.ProcessingJobEntity(id, "live-sync-test", "whistle", "PROCESSING", 1, total,
            "Transcribing chunk 2 of $total…", System.currentTimeMillis()))
        var unit = 1
        while (currentCoroutineContext().isActive) {
            unit++
            app.database.withTransaction {
                val row = raw[unit % raw.size]
                dao.putSegments(listOf(row.copy(confidence = (row.confidence ?: 1f) * .999f)))
                dao.findVersion(id)?.let { dao.putVersion(it.copy(transcriptRevision = it.transcriptRevision + 1, updatedAt = System.currentTimeMillis())) }
                app.database.processingDao().find(id)?.let { app.database.processingDao().put(it.copy(completedUnits = unit % total, message = "Transcribing chunk $unit of $total…", updatedAt = System.currentTimeMillis())) }
            }
            if (!epub) runCatching { app.generatedEpub.refreshReading(id) }
            if (epub && unit % 4 == 0) runCatching { app.importedEpub.align(id) }
            delay(250)
        }
    }

    private fun importEpub(rows: List<com.example.readalong.data.local.TranscriptEntity>) {
        val file = java.io.File(app.cacheDir, "live-sync-${System.nanoTime()}.epub")
        // Several copies of the book's text so the page has plenty to scroll.
        val paragraphs = (0 until 6).joinToString("") { copy -> "<h2>Part ${copy + 1}</h2>" + rows.joinToString("") { "<p>${it.text.replace("&", "&amp;").replace("<", "&lt;")}</p>" } }
        val entries = mapOf("mimetype" to "application/epub+zip", "META-INF/container.xml" to """<container><rootfile full-path="package.opf"/></container>""",
            "package.opf" to """<package><metadata><title>Live sync edition</title></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
            "chapter.xhtml" to "<html><body><h1>At the orchard</h1>$paragraphs</body></html>")
        java.util.zip.ZipOutputStream(file.outputStream()).use { z -> entries.forEach { (name, text) -> z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        runBlocking {
            app.importedEpub.import(id, android.net.Uri.fromFile(file))
            withTimeout(60_000) { app.importedEpub.observe(id).first { it?.state in listOf("ALIGNED", "PARTIAL", "UNUSABLE", "FAILED") } }
            app.importedEpub.preference(id, "EPUB")
        }
    }

    private fun texts(node: androidx.compose.ui.semantics.SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun firstParagraph(): Pair<String, Float>? = compose.onAllNodesWithTag("reader_paragraph").fetchSemanticsNodes()
        .filter { it.boundsInRoot.top > 0f && texts(it).length > 20 }.minByOrNull { it.boundsInRoot.top }?.let { texts(it) to it.boundsInRoot.top }
    private fun paragraphTop(text: String): Float? = compose.onAllNodesWithTag("reader_paragraph").fetchSemanticsNodes()
        .firstOrNull { texts(it) == text }?.boundsInRoot?.top
}
