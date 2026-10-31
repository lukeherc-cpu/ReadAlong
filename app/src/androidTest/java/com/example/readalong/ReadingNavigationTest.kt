package com.example.readalong

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.example.readalong.data.reading.toEntity
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadingNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun dismissFirstUseSetup() = runBlocking {
        val app = compose.activity.application as ReadAlongApplication
        app.speechModel.dismissSetup()
        app.appPreferences.update { com.example.readalong.data.preferences.AppPreferences(batteryWarningSeen = true, homeGuideHidden = true, playerGuideHidden = true, readerGuideHidden = true) }
        app.database.bookDao().putMetadata(com.example.readalong.data.local.LibraryMetadataEntity("battery_prompt_v1", "test"))
        app.bookRepository.observeBooks().first()
        app.database.bookDao().insertBooks(com.example.readalong.data.book.DemoBooks.books)
        app.database.bookDao().insertProgress(com.example.readalong.data.book.DemoBooks.progress)
        app.transcriptRepository.prepareDemo("demo-night-orchard")
        Unit
    }

    @org.junit.After fun removeTestSampleBooks() = kotlinx.coroutines.runBlocking {
        val app = compose.activity.application as ReadAlongApplication
        app.database.bookDao().removeSamples()
        if (app.database.bookDao().metadata("current_book")?.startsWith("demo-") == true) app.database.bookDao().removeMetadata("current_book")
        Unit
    }

    private fun flipToPreview() {
        compose.onNodeWithTag("flip_cover", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("follow_card", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun openFollow() {
        flipToPreview()
        compose.onNodeWithContentDescription("Read in full screen").performClick()
    }

    private fun revealControls() {
        if (compose.onAllNodesWithTag("reader_reveal_controls").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("reader_reveal_controls").performTouchInput {
                click(androidx.compose.ui.geometry.Offset(8f, 8f))
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun flipExpandAndSingleBackStayUsableWhileAudioAndWhistleRun() {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("whistleModel") == "true")
        val app = compose.activity.application as ReadAlongApplication
        org.junit.Assume.assumeTrue(runBlocking { app.speechModel.available() })
        val wave = java.io.File(java.io.File(app.cacheDir, "exports").apply { mkdirs() }, "transition-load-${System.nanoTime()}.wav")
        var id: String? = null
        try {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-natural-loop.wav")).use { input -> wave.outputStream().use { input.copyTo(it) } }
            id = runBlocking {
                val book = app.audioImporter.importFiles(listOf(androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", wave)), false).bookIds.single()
                app.database.bookDao().putMetadata(com.example.readalong.data.local.LibraryMetadataEntity("epub_prompt:$book", "true"))
                book
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Search library").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Search library").performClick()
            compose.onNodeWithText("Search title or author").performTextInput("transition-load")
            compose.onNodeWithTag("book:$id").performClick()
            compose.waitUntil(10_000) { app.playback.state.value.bookId == id && !app.playback.state.value.isLoading }
            runBlocking { app.processing.transcribeNext(id!!) }
            compose.runOnUiThread { app.playback.listenFrom(0) }
            compose.waitUntil(10_000) { app.playback.state.value.isPlaying }
            flipToPreview()
            compose.onNodeWithContentDescription("Read in full screen").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("follow_passages").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(app.playback.state.value.isPlaying)
            compose.onNodeWithContentDescription("Read in full screen").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("follow_passages").fetchSemanticsNodes().isNotEmpty() }
            revealControls()
            assertTrue(app.playback.state.value.isPlaying)
            compose.onNodeWithContentDescription("Pause").performClick()
            compose.waitUntil(5000) { !app.playback.state.value.isPlaying }
            compose.onNodeWithContentDescription("Player").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isNotEmpty() }
            val job = runBlocking { app.database.processingDao().find(id!!)!! }
            assertTrue(job.state in listOf("QUEUED", "PROCESSING", "PARTIAL_READY", "GENERATING_EPUB", "READY"))
        } finally {
            compose.runOnUiThread { app.playback.pause() }
            runBlocking { id?.let { app.processing.cancel(it); app.playback.releaseBook(it); app.database.bookDao().removeBook(it) } }
            wave.delete()
        }
    }

    @Test fun privateCoverShowsBlurredPlayerWithoutChangingReaderColors() {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("visualAssets") == "true")
        val app = compose.activity.application as ReadAlongApplication
        val base = com.example.readalong.data.book.DemoBooks.books.first { it.id == "demo-night-orchard" }
        val image = java.io.File(java.io.File(app.cacheDir, "exports").apply { mkdirs() }, "visual-cover.jpg")
        try {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-visual-cover.jpg")).use { input -> image.outputStream().use { input.copyTo(it) } }
            org.junit.Assert.assertTrue(image.length() > 1000)
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", image).toString()
            runBlocking { app.database.bookDao().upsertBooks(listOf(base.copy(coverUri = uri))) }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Search library").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Search library").performClick()
            compose.onNodeWithText("Search title or author").performTextInput("Mira")
            compose.onNodeWithTag("book:${base.id}").performClick()
            compose.waitUntil(5000) { app.playback.state.value.bookId == base.id }
            compose.waitForIdle()
            // Native image decode is asynchronous; wait for sampled background pixels to vary.
            compose.waitUntil(10_000) {
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                bitmap.getPixel(8, bitmap.height / 4) != bitmap.getPixel(8, bitmap.height * 3 / 4)
            }
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> java.io.File(app.getExternalFilesDir(null), "verification-blurred-player.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        } finally { runBlocking { app.database.bookDao().upsertBooks(listOf(base)) }; image.delete() }
    }

    @Test fun sharedPreviewBoundsExpandAndReverseWithoutChangingAudioPosition() {
        val app = compose.activity.application as ReadAlongApplication
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Search library").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Search library").performClick()
        compose.onNodeWithText("Search title or author").performTextInput("Mira")
        compose.onNodeWithTag("book:demo-night-orchard").performClick()
        compose.waitUntil(5000) { app.playback.state.value.bookId == "demo-night-orchard" }
        compose.runOnUiThread { app.playback.pause(); app.playback.seek(20_000) }
        compose.waitUntil(5000) { app.playback.state.value.positionMs == 20_000L }
        flipToPreview()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> java.io.File(app.getExternalFilesDir(null), "verification-reader-before.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        val previewLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("All day the branches", substring = true, useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(previewLayouts) }
        val previewLayout = previewLayouts.single()
        val before = compose.onNodeWithTag("follow_card", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val character = previewLayout.layoutInput.text.text.indexOf("All day")
        val previewLineY = compose.onNodeWithText(previewLayout.layoutInput.text.text, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y +
            previewLayout.getLineTop(previewLayout.getLineForOffset(character)) - before.top
        try {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("Read in full screen").performClick()
            compose.mainClock.advanceTimeBy(150)
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                java.io.File(app.getExternalFilesDir(null), "verification-reader-transform-mid.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                var brightPixels = 0
                for (y in bitmap.height / 10 until bitmap.height / 2 step 2) for (x in bitmap.width / 10 until bitmap.width * 9 / 10 step 2) {
                    val color = bitmap.getPixel(x, y)
                    if (android.graphics.Color.red(color) > 200 && android.graphics.Color.green(color) > 200 && android.graphics.Color.blue(color) > 200) brightPixels++
                }
                assertTrue("Reading text must remain visible during expansion", brightPixels > 100)
            }
            assertEquals(20_000L, app.playback.state.value.positionMs)
            assertFalse(app.playback.state.value.isPlaying)
            compose.mainClock.advanceTimeBy(500)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("follow_passages").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val expandedLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(previewLayout.layoutInput.text.text, useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(expandedLayouts) }
        val expandedLayout = expandedLayouts.single()
        assertEquals("Expansion must retain paragraph line wrapping", previewLayout.size.width, expandedLayout.size.width)
        assertEquals(previewLayout.lineCount, expandedLayout.lineCount)
        val expandedLineY = compose.onNodeWithText(previewLayout.layoutInput.text.text, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y +
            expandedLayout.getLineTop(expandedLayout.getLineForOffset(character))
        assertEquals("The highlighted line must keep its position at handoff", previewLineY, expandedLineY, 4f)
        (0 until previewLayout.lineCount).forEach { assertEquals(previewLayout.getLineEnd(it), expandedLayout.getLineEnd(it)) }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> java.io.File(app.getExternalFilesDir(null), "verification-reader-after.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Read in full screen").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("follow_card", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(before.width, after.width, 2f); assertEquals(before.top, after.top, 3f)
        assertEquals(20_000L, app.playback.state.value.positionMs)
    }

    @Test fun preferredOpeningNamedBookmarksAndCustomIntervalsWorkTogether() {
        val app = compose.activity.application as ReadAlongApplication
        val id = "demo-night-orchard"
        runBlocking { app.appPreferences.update { it.copy(openingView = "FULLSCREEN", playerBack = 11, playerForward = 13, readerBack = 7, readerForward = 9) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Search library").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Search library").performClick()
        compose.onNodeWithText("Search title or author").performTextInput("Mira")
        compose.onNodeWithTag("book:$id").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("follow_passages").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { app.playback.pause(); app.playback.seek(20_000) }
        compose.onNodeWithContentDescription("Back 7 seconds").performClick()
        compose.waitUntil(5000) { app.playback.state.value.positionMs == 13_000L }
        compose.onNodeWithContentDescription("Forward 9 seconds").performClick()
        compose.waitUntil(5000) { app.playback.state.value.positionMs == 22_000L }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Collapse player").assertExists()
        compose.onNodeWithContentDescription("Rewind 11 seconds").performScrollTo().performClick()
        compose.waitUntil(5000) { app.playback.state.value.positionMs == 11_000L }
        compose.onNodeWithContentDescription("Forward 13 seconds").performClick()
        compose.waitUntil(5000) { app.playback.state.value.positionMs == 24_000L }
        val before = runBlocking { app.transcriptRepository.bookmarks(id).first().size }
        compose.onNodeWithContentDescription("Bookmarks").performScrollTo().performClick()
        assertEquals(before, runBlocking { app.transcriptRepository.bookmarks(id).first().size })
        compose.onNodeWithContentDescription("Add bookmark").performClick()
        compose.onNodeWithText("Bookmark name").performTextInput("A saved moment")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("A saved moment").fetchSemanticsNodes().isNotEmpty() }
        val bookmark = runBlocking { app.transcriptRepository.bookmarks(id).first().first { it.label == "A saved moment" } }
        assertEquals(24_000L, bookmark.audioMs)
        compose.onNodeWithText("Close").performClick()
        compose.runOnUiThread { app.playback.seek(0) }
        compose.onNodeWithContentDescription("Bookmarks").performClick()
        compose.onNodeWithText("A saved moment").performClick()
        compose.waitUntil(5000) { app.playback.state.value.positionMs == 24_000L }
        assertFalse(app.playback.state.value.isPlaying)
        runBlocking { app.appPreferences.update { com.example.readalong.data.preferences.AppPreferences(batteryWarningSeen = true, homeGuideHidden = true, playerGuideHidden = true, readerGuideHidden = true) } }
    }

    @Test fun pausedSentenceTapSeeksAndPlayingTapsRevealThenSeekAndHideControls() {
        val app = compose.activity.application as ReadAlongApplication
        val id = "demo-night-orchard"
        val text = "The first sentence ends. The second sentence begins here. The final sentence follows."
        val split = text.indexOf("The second")
        val final = text.indexOf("The final")
        runBlocking {
            val words = text.split(' ').mapIndexed { i, word ->
                com.example.readalong.domain.model.TranscriptWord(word, 1000L + i * 1000, 1800L + i * 1000)
            }
            app.database.readingDao().clearTranscript(id)
            val source = com.example.readalong.domain.model.TranscriptSegment("sentence-test", id, "chapter", 1000, 20_000, text, words = words)
            app.database.readingDao().putSegments(listOf(source.toEntity()))
            val version = app.database.readingDao().findVersion(id)!!
            app.database.readingDao().putVersion(version.copy(transcriptRevision = version.transcriptRevision + 1))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Add audiobook").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Search library").performClick()
        compose.onNodeWithText("Search title or author").performTextInput("Mira")
        compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:$id"))
        compose.onNodeWithTag("book:$id").performClick()
        compose.waitUntil(5000) { app.playback.state.value.bookId == id }
        compose.runOnUiThread { app.playback.pause(); app.playback.seek(1000) }
        openFollow()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bounds = layouts.single().getBoundingBox(text.indexOf("here"))
        compose.onNodeWithText(text).performTouchInput { click(bounds.center) }
        compose.waitUntil(5000) { app.playback.state.value.isPlaying && app.playback.state.value.positionMs in 5000..6500 }
        compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
        val beforePause = app.playback.state.value.positionMs
        // Touch a different sentence while playing: reveal controls without pausing or seeking.
        compose.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action -> layouts.clear(); action(layouts) }
        val firstBounds = layouts.single().getBoundingBox(2)
        compose.onNodeWithText(text).performTouchInput { click(firstBounds.center) }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(app.playback.state.value.isPlaying)
        assertTrue(app.playback.state.value.positionMs >= beforePause)
        assertTrue(app.playback.state.value.positionMs < beforePause + 3000)
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        val painted = compose.onNodeWithText(text).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
        assertTrue("The whole second sentence is white", painted.spanStyles.any { it.start == split && it.end == final - 1 && it.item.color == androidx.compose.ui.graphics.Color.White })
        // With controls already visible, the next text tap seeks and returns to immersive reading.
        compose.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action -> layouts.clear(); action(layouts) }
        compose.onNodeWithText(text).performTouchInput { click(layouts.single().getBoundingBox(2).center) }
        compose.waitUntil(5000) { app.playback.state.value.isPlaying && app.playback.state.value.positionMs in 1000..2500 }
        compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
        compose.runOnUiThread { app.playback.pause(); app.playback.seek(5000) }
        compose.onNodeWithContentDescription("Next sentence").performClick()
        compose.waitUntil(5000) { app.playback.state.value.positionMs == 10_000L }
        assertFalse(app.playback.state.value.isPlaying)
        compose.onNodeWithContentDescription("Previous sentence").performClick()
        compose.waitUntil(5000) { app.playback.state.value.positionMs == 5000L }
        compose.onNodeWithContentDescription("Back 5 seconds").performClick()
        compose.waitUntil(5000) { app.playback.state.value.positionMs == 0L }
        compose.onNodeWithContentDescription("Forward 5 seconds").performClick()
        compose.waitUntil(5000) { app.playback.state.value.positionMs == 5000L }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Refresh generated text").assertDoesNotExist()
        compose.runOnUiThread { app.playback.pause() }
    }

    @Test fun controlsAutoHideWithoutMovingTextAndScrollingDoesNotExtendTheirTimer() {
        val app = compose.activity.application as ReadAlongApplication
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Add audiobook").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Search library").performClick()
        compose.onNodeWithText("Search title or author").performTextInput("Mira")
        compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:demo-night-orchard"))
        compose.onNodeWithTag("book:demo-night-orchard").performClick()
        compose.runOnUiThread { app.playback.pause() }
        openFollow(); revealControls()
        compose.mainClock.autoAdvance = false
        val before = compose.onNodeWithTag("follow_passages").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(2200)
        compose.onNodeWithTag("follow_passages").performTouchInput { swipeUp(startY = height * .65f, endY = height * .25f, durationMillis = 150) }
        compose.mainClock.advanceTimeBy(1200)
        compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
        val after = compose.onNodeWithTag("follow_passages").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        compose.mainClock.autoAdvance = true
        revealControls()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test fun longParagraphFollowsLaterAnchorsWithoutChangingParagraphIndex() {
        val app = compose.activity.application as ReadAlongApplication
        val id = "demo-night-orchard"
        val initial = runBlocking { app.bookRepository.observeBook(id).first()!!.positionMs }
        val phrases = (0 until 80).map { "At marker $it the traveler crossed the quiet orchard and listened to the river flowing beneath the old wooden bridge." }
        val text = phrases.joinToString(" ")
        var offset = 0
        val anchors = org.json.JSONArray()
        phrases.forEachIndexed { i, phrase -> anchors.put(org.json.JSONObject().apply {
            put("id", "original-$i"); put("start", offset); put("end", offset + phrase.length)
            put("startMs", i * 10_000L); put("endMs", (i + 1) * 10_000L)
        }); offset += phrase.length + 1 }
        try {
            runBlocking {
                app.database.importedEpubDao().put(com.example.readalong.data.local.ImportedEpubEntity(id, "long-original", "", "Original long paragraph", "ALIGNED", "Matched", "EPUB", 0, 80, 80, 80, 80, 0))
                app.database.importedEpubDao().putBlocks(listOf(com.example.readalong.data.local.ImportedEpubBlockEntity(id, "long-original", 0, 0, "chapter", text, "P", "[]", false, anchors.toString(), 0, 800_000, 1f)))
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Add audiobook").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Search library").performClick()
            compose.onNodeWithText("Search title or author").performTextInput("Mira")
            compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:$id"))
            compose.onNodeWithTag("book:$id").performClick()
            compose.waitUntil(5000) { app.playback.state.value.bookId == id }
            compose.runOnUiThread { app.playback.pause(); app.playback.seek(0) }
            openFollow()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnUiThread { app.playback.seek(650_000) }
            compose.waitUntil(10_000) {
                compose.onNodeWithTag("follow_passages").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value() > 0f
            }
            compose.onNodeWithTag("follow_passages").performTouchInput { swipeUp(startY = height * .65f, endY = height * .25f) }
            compose.waitForIdle()
            compose.onNodeWithText("Return to current position").assertIsDisplayed()
            val beforeTap = compose.onNodeWithTag("follow_passages").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            compose.onNodeWithTag("follow_passages").performTouchInput { click(center) }
            compose.waitForIdle()
            val afterTap = compose.onNodeWithTag("follow_passages").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Tapping a scrolled paragraph must not reset its viewport", afterTap >= beforeTap * .85f)
            // A new alignment revision must not reopen the book at its opening paragraph.
            runBlocking {
                val edition = app.database.importedEpubDao().find(id)!!
                app.database.importedEpubDao().put(edition.copy(alignedRevision = edition.alignedRevision + 1))
            }
            compose.waitForIdle()
            assertTrue(compose.onNodeWithTag("follow_passages").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value() > 0f)

        } finally {
            runBlocking { app.database.importedEpubDao().remove(id); app.database.importedEpubDao().clearBlocks(id) }
            compose.runOnUiThread { app.playback.pause(); app.playback.seek(initial) }
        }
    }

    @Test fun previewFollowsPlaybackAndSentenceSearchSeeksAudioInFullScreen() {
        val app = compose.activity.application as ReadAlongApplication
        val id = "demo-night-orchard"
        val initial = runBlocking { app.bookRepository.observeBook(id).first()!!.positionMs }
        try {
            val rows = runBlocking { app.transcriptRepository.prepareDemo(id); app.database.readingDao().segments(id) }
            val target = rows.last()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Add audiobook").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Search library").performClick()
            compose.onNodeWithText("Search title or author").performTextInput("Mira")
            compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:$id"))
            compose.onNodeWithTag("book:$id").performClick()
            compose.waitUntil(5000) { app.playback.state.value.bookId == id }
            flipToPreview()
            val card = compose.onNodeWithTag("follow_card", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("Preview stays square", card.width, card.height, 2f)
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                java.io.File(app.getExternalFilesDir(null), "verification-player-flip.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithTag("follow_card", useUnmergedTree = true).performClick() // Tapping the preview flips back to the cover.
            compose.waitUntil(5000) { compose.onAllNodesWithTag("flip_cover", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            flipToPreview()
            compose.waitUntil(5000) { app.playback.state.value.bookId == id }
            compose.runOnUiThread { app.playback.pause(); app.playback.seek(target.startMs) }
            compose.waitUntil(5000) { app.playback.state.value.positionMs == target.startMs }
            try {
                compose.waitUntil(10_000) { compose.onAllNodes(hasText(target.text.take(30), substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("Preview at ${app.playback.state.value.positionMs}: ${compose.onNodeWithTag("follow_preview", useUnmergedTree = true).fetchSemanticsNode().config}; expected ${target.text.take(30)}", error)
            }
            compose.onNodeWithContentDescription("Read in full screen").performClick()
            compose.onAllNodesWithText("Follow Along", substring = false).assertCountEquals(0)
            compose.onNodeWithContentDescription("Search text").assertIsDisplayed()
            revealControls()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Font size", substring = true).assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithContentDescription("Search text").performClick()
            compose.onNodeWithTag("reader_search").performTextInput(rows[1].text.take(40).uppercase())
            val audio = "Audio sync · ${com.example.readalong.playback.clockTime(rows[1].startMs)}"
            compose.waitUntil(10_000) { compose.onAllNodesWithText(audio).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(audio).performClick()
            try {
                compose.waitUntil(5000) { app.playback.state.value.positionMs in rows[1].startMs..rows[1].endMs }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap().let { bitmap ->
                    java.io.File(app.getExternalFilesDir(null), "verification-search-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                throw AssertionError("Search selected audio at ${app.playback.state.value.positionMs}; expected ${rows[1].startMs}..${rows[1].endMs}; text ${rows[1].text}", error)
            }
            assertFalse("Search selection must not start playback", app.playback.state.value.isPlaying)
            // Search remains paused even when choosing a second passage.
            compose.onNodeWithContentDescription("Search text").performClick()
            compose.onNodeWithTag("reader_search").performTextReplacement(rows.last().text.substringBefore('.').uppercase())
            val readHit = "Audio sync · ${com.example.readalong.playback.clockTime(rows.last().startMs)}"
            compose.waitUntil(10_000) { compose.onAllNodesWithText(readHit).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(readHit).performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(rows.last().text.take(30), substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(rows.last().text.take(30), substring = true), useUnmergedTree = true).assertIsDisplayed()
            assertFalse(app.playback.state.value.isPlaying)
        } finally { compose.runOnUiThread { app.playback.pause(); app.playback.seek(initial) } }
    }
    @Test fun followAndQuietReadingShareOnePlayerAndPreserveGapPositions() {
        val app = compose.activity.application as ReadAlongApplication
        val bookId = "demo-night-orchard"
        val initial = runBlocking { app.bookRepository.observeBook(bookId).first()!!.positionMs }
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Add audiobook").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Search library").performClick()
            compose.onNodeWithText("Search title or author").performTextInput("Mira")
            compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:$bookId"))
            compose.onNodeWithTag("book:$bookId").performClick()
            compose.waitUntil(5000) { app.playback.state.value.bookId == bookId }
            compose.runOnUiThread { app.playback.pause(); app.playback.seek(500_000) }
            openFollow()
            revealControls()
            compose.onNodeWithText("Follow Along").assertDoesNotExist()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnUiThread { app.playback.pause() }
            compose.onNodeWithContentDescription("Play").assertIsDisplayed()
            compose.onNodeWithContentDescription("Play").performClick()
            compose.waitUntil(5000) { app.playback.state.value.isPlaying }
            assertTrue("Untranscribed audio must not jump to the beginning", app.playback.state.value.positionMs in 500_000..510_000)
            revealControls()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnUiThread { app.playback.pause() }
            compose.waitUntil(5000) { !app.playback.state.value.isPlaying }
            compose.onNodeWithContentDescription("Contents").performClick()
            compose.onNodeWithText("Chapter 1").performClick()
            compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("follow_passages")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("follow_passages").performTouchInput { swipeUp() }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Play").performClick()
            compose.waitUntil(5000) { app.playback.state.value.isPlaying }
            assertTrue(app.playback.state.value.positionMs < 216_000)
            compose.onNodeWithText("Follow Along").assertDoesNotExist()
            compose.onAllNodesWithText("READ").assertCountEquals(0)
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithContentDescription("Collapse player").assertExists()
        } finally { compose.runOnUiThread { app.playback.pause(); app.playback.seek(initial) } }
    }
    @Test fun fullscreenReadingSurfaceStaysVisibleAcrossLiveTranscriptPublications() {
        val app = compose.activity.application as ReadAlongApplication
        val id = "demo-night-orchard"
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Search library").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Search library").performClick()
        compose.onNodeWithText("Search title or author").performTextInput("Mira")
        compose.onNodeWithTag("book:$id").performClick()
        compose.waitUntil(5000) { app.playback.state.value.bookId == id }
        compose.runOnUiThread { app.playback.pause(); app.playback.seek(20_000) }
        openFollow()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("follow_passages").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val update = background.async {
            val raw = app.database.readingDao().segments(id).first()
            repeat(6) { revision ->
                app.database.withTransaction {
                    app.database.readingDao().putSegments(listOf(raw.copy(text = "Latest live text revision $revision. " + raw.text)))
                    val version = app.database.readingDao().findVersion(id)!!
                    app.database.readingDao().putVersion(version.copy(transcriptRevision = version.transcriptRevision + 1))
                }
                app.generatedEpub.refreshReading(id)
                kotlinx.coroutines.delay(100)
            }
        }
        try {
            compose.mainClock.autoAdvance = false
            repeat(70) {
                compose.mainClock.advanceTimeBy(16)
                compose.onNodeWithTag("follow_passages").assertExists()
                compose.onNodeWithContentDescription("Settings").assertExists()
                Thread.sleep(15)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            runBlocking { update.await() }; background.cancel()
        }
        val latest = runBlocking {
            val version = app.database.readingDao().findVersion(id)!!
            app.database.readingDao().editionPage(id, version.activeEditionId!!, 80, 0)
        }
        java.io.File(app.getExternalFilesDir(null), "verification-live-reader.txt").writeText(latest.joinToString("\n") { it.text } + "\nUI:\n" + compose.onNodeWithTag("follow_passages").printToString())
        assertTrue("Published text must contain the latest revision", latest.any { it.text.contains("Latest live text revision 5.") })
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Latest live text revision 5.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(app.playback.state.value.isPlaying)
        assertEquals(20_000L, app.playback.state.value.positionMs)
    }

    @Test fun passageCorrectionAndUndoKeepOriginalRecognition() {
        val app = compose.activity.application as ReadAlongApplication
        val id = "demo-night-orchard"
        val initial = runBlocking { app.bookRepository.observeBook(id).first()!!.positionMs }
        var segmentId: String? = null
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Add audiobook").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Search library").performClick()
            compose.onNodeWithText("Search title or author").performTextInput("Mira")
            compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:$id"))
            compose.onNodeWithTag("book:$id").performClick()
            compose.waitUntil(5000) { app.playback.state.value.bookId == id }
            compose.runOnUiThread { app.playback.pause(); app.playback.seek(0) }
            openFollow()
            val raw = runBlocking { app.database.readingDao().segments(id).first() }
            segmentId = raw.id
            compose.waitUntil(10_000) { compose.onAllNodesWithText(raw.text, substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(raw.text, substring = true).performTouchInput { longClick(androidx.compose.ui.geometry.Offset(12f, 12f)) }
            compose.onNodeWithText("Corrected text").performTextReplacement("A gorilla waits by the river.")
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("A gorilla waits by the river.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(raw, runBlocking { app.database.readingDao().segment(raw.id) })
            compose.waitForIdle()
            compose.onNodeWithText("A gorilla waits by the river.", substring = true).performTouchInput { longClick(androidx.compose.ui.geometry.Offset(12f, 12f)) }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Restore original recognition", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Restore original recognition", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(raw.text, substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertNull(runBlocking { app.database.readingDao().correction(id, raw.id) })
        } finally {
            runBlocking { segmentId?.let { app.database.readingDao().deleteCorrection(id, it) } }
            compose.runOnUiThread { app.playback.pause(); app.playback.seek(initial) }
        }
    }

    @Test fun importedEpubTapSeeksButMusicalPassageHasNoFabricatedAudioPosition() {
        val app = compose.activity.application as ReadAlongApplication
        val id = "demo-night-orchard"
        val initial = runBlocking { app.bookRepository.observeBook(id).first()!!.positionMs }
        val rows = runBlocking { app.transcriptRepository.prepareDemo(id); app.database.readingDao().segments(id) }
        val file = java.io.File(app.cacheDir, "original-ui-edition-${System.nanoTime()}.epub")
        val paragraphs = rows.mapIndexed { i, row -> "<p>${row.text.replace("&", "&amp;").replace("<", "&lt;")}</p>" + if (i == 0) "<p>♪ ♫ ♬</p>" else "" }.joinToString("")
        val entries = mapOf("mimetype" to "application/epub+zip", "META-INF/container.xml" to """<container><rootfile full-path="package.opf"/></container>""",
            "package.opf" to """<package><metadata><title>Original UI edition</title></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
            "chapter.xhtml" to "<html><body><h1>At the orchard</h1>$paragraphs</body></html>")
        java.util.zip.ZipOutputStream(file.outputStream()).use { z -> entries.forEach { (name, text) -> z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        try {
            runBlocking {
                app.importedEpub.import(id, android.net.Uri.fromFile(file))
                kotlinx.coroutines.withTimeout(60_000) { app.importedEpub.observe(id).first { it?.state in listOf("ALIGNED", "PARTIAL", "UNUSABLE", "FAILED") } }
                app.importedEpub.preference(id, "EPUB")
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Add audiobook").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Search library").performClick()
            compose.onNodeWithText("Search title or author").performTextInput("Mira")
            compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:$id"))
            compose.onNodeWithTag("book:$id").performClick()
            compose.waitUntil(5000) { app.playback.state.value.bookId == id }
            compose.runOnUiThread { app.playback.pause(); app.playback.seek(rows.first().startMs) }
            openFollow()
            revealControls()
            compose.onNodeWithText("My EPUB · original text").assertDoesNotExist()
            compose.onNodeWithTag("follow_passages").performScrollToNode(hasText(rows[1].text))
            compose.onNodeWithText(rows[1].text).performClick()
            try {
                compose.waitUntil(5000) { app.playback.state.value.positionMs in rows[1].startMs..rows[1].endMs }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap().let { bitmap ->
                    java.io.File(app.getExternalFilesDir(null), "verification-search-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                throw AssertionError("Search selected audio at ${app.playback.state.value.positionMs}; expected ${rows[1].startMs}..${rows[1].endMs}; text ${rows[1].text}", error)
            }
            revealControls()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnUiThread { app.playback.pause() }
            compose.onNodeWithTag("follow_passages").performScrollToNode(hasText("♪ ♫ ♬"))
            compose.onNodeWithText("♪ ♫ ♬").performClick()
            compose.waitUntil(5000) { app.playback.state.value.isPlaying && app.playback.state.value.positionMs < rows[1].startMs }
            assertEquals("Unmatched symbols still have no invented timestamps", -1L, runBlocking { app.database.importedEpubDao().blocks(id, app.database.importedEpubDao().find(id)!!.editionId).first { it.text == "♪ ♫ ♬" }.startMs })
            revealControls()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Cancel").performClick()
            compose.runOnUiThread { app.playback.pause() }
            compose.onNodeWithTag("follow_passages").performScrollToNode(hasText("♪ ♫ ♬"))
            compose.onNodeWithText("♪ ♫ ♬").performTouchInput { longClick() }
            compose.waitUntil(5000) { runCatching { compose.onNodeWithText("Set sync point").assertIsDisplayed(); true }.getOrDefault(false) }
            compose.onNodeWithText("Set sync point").assertIsDisplayed()
            compose.onNodeWithText("Audio start").performTextReplacement("0:05")
            compose.onNodeWithText("Audio end").performTextReplacement("0:08")
            compose.onNodeWithText("Save sync").performClick()
            compose.waitUntil(10_000) { runBlocking { app.database.importedEpubDao().find(id)!!.manualCount == 1 } }
            compose.onNodeWithContentDescription("Play").assertIsEnabled()
            compose.onNodeWithTag("follow_passages").performScrollToNode(hasText("♪ ♫ ♬"))
            compose.onNodeWithText("♪ ♫ ♬").performTouchInput { longClick() }
            compose.onNodeWithText("Reset to automatic sync", useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { app.database.importedEpubDao().find(id)!!.manualCount == 0 } }

            compose.onNodeWithTag("follow_passages").performScrollToNode(hasText(rows.first().text))
            compose.onNodeWithText(rows.first().text).performClick()
            compose.waitUntil(5000) { app.playback.state.value.isPlaying }
            assertTrue(app.playback.state.value.positionMs < rows[1].startMs)
        } finally {
            runBlocking { app.importedEpub.remove(id) }
            file.delete()
            compose.runOnUiThread { app.playback.pause(); app.playback.seek(initial) }
        }
    }

}
