package com.example.readalong

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import com.example.readalong.data.local.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QueueNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun ready() {
        val app = compose.activity.application as ReadAlongApplication
        runBlocking { app.appPreferences.update { it.copy(setupStage = 3, batteryWarningSeen = true) }; app.speechModel.dismissSetup() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("launch_logo").fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithContentDescription("Transcription queue").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun logoOpensQueuePlusAddsWithoutStartingAndHandleReordersBeforeTrash() {
        val app = compose.activity.application as ReadAlongApplication
        val ids = (0..2).map { "queue-ui-${System.nanoTime()}-$it" }
        val original = runBlocking { app.appPreferences.preferences.first() }
        try {
            runBlocking { ids.forEachIndexed { i, id ->
                app.database.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, title = "Queue sample ${'A' + i}", isDemo = false, addedAt = i.toLong())))
                app.processing.imported(id)
                if (i < 2) app.processing.addToQueue(id)
            } }
            ready()
            compose.onNodeWithContentDescription("Transcription queue").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("transcription_queue").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Resume top 3").assertDoesNotExist()
            compose.onNodeWithContentDescription("Pause top 3").assertDoesNotExist()
            compose.onNodeWithContentDescription("Add book to queue").performClick()
            val choices = compose.onAllNodesWithText("Queue sample C")
            choices[choices.fetchSemanticsNodes().lastIndex].performClick()
            compose.waitUntil(5000) { runBlocking { app.database.processingDao().find(ids[2])!!.state == "WAITING_QUEUE" } }
            assertEquals(0, runBlocking { app.database.processingDao().find(ids[2])!!.completedUnits })
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> java.io.File(app.getExternalFilesDir(null), "verification-queue.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            compose.onNodeWithTag("transcription_queue").performScrollToNode(hasContentDescription("Reorder Queue sample A"))
            val first = compose.onNodeWithContentDescription("Reorder Queue sample A").fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithContentDescription("Reorder Queue sample B").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription("Reorder Queue sample A").performTouchInput {
                down(center); advanceEventTime(16); moveBy(Offset(0f, second.center.y-first.center.y), 500); up()
            }
            compose.waitUntil(5000) { runBlocking { app.database.processingDao().registeredQueue().filter { it.bookId in ids }.first().bookId == ids[1] } }
            compose.onNodeWithContentDescription("Remove Queue sample A from queue").performClick()
            compose.waitUntil(5000) { runBlocking { app.database.processingDao().find(ids[0])!!.state == "WAITING_SELECTION" } }
            compose.onNodeWithText("Not transcribed").assertExists()
            compose.onNodeWithText("Add all").performScrollTo().performClick()
            compose.waitUntil(5000) { runBlocking { app.database.processingDao().find(ids[0])!!.state == "WAITING_QUEUE" } }
            assertTrue(runBlocking { app.database.processingDao().unfinished().none { it.bookId in ids } })
            compose.onNodeWithContentDescription("Back to library").performClick()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithContentDescription("Animate transcription logo").performScrollTo().performClick()
            compose.waitUntil(5000) { !runBlocking { app.appPreferences.preferences.first().animateTranscriptionLogo } }
        } finally { runBlocking { ids.forEach { app.database.bookDao().removeBook(it) }; app.appPreferences.update { original } } }
    }
    @Test fun repeatedFastFolderBackAndSettingsCannotPopOrNavigateADestroyedEntry() {
        ready()
        try {
            compose.mainClock.autoAdvance = false
            repeat(20) {
                val settings = compose.onNodeWithContentDescription("Settings").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
                compose.onNodeWithContentDescription("Audiobook folders").performClick()
                compose.mainClock.advanceTimeBy(32)
                val back = compose.onNodeWithText("Back to library").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
                compose.runOnUiThread { back(); back(); settings() }
                compose.mainClock.advanceTimeBy(32)
                assertTrue(compose.onAllNodesWithText("Settings", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
                val returns = compose.onAllNodesWithContentDescription("Back to library")
                returns[returns.fetchSemanticsNodes().lastIndex].performClick()
                compose.mainClock.advanceTimeBy(32)
                assertTrue(compose.onAllNodesWithTag("library_grid").fetchSemanticsNodes().isNotEmpty())
                // Each trip is deliberately fast; settle between trips so the next gesture
                // targets the current destination rather than a retained outgoing test node.
                compose.mainClock.advanceTimeBy(750)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
