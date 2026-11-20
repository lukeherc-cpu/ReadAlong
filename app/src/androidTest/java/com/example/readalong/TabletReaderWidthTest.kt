package com.example.readalong

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * A landscape tablet (the Xiaomi Pad 7's 3:2 window, about 1600 x 1068 dp): the player shows the text
 * preview as a small card beside the controls. Expanding it must give the reader the full page width,
 * not leave its text column as narrow as the card.
 */
class TabletReaderWidthTest {
    companion object {
        private fun shell(command: String) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { pipe ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
            }
        }

        // Set before the activity starts, so the test never sees a configuration change.
        @BeforeClass @JvmStatic fun tabletWindow() {
            shell("wm size 2400x1602")
            shell("wm density 240")
            Thread.sleep(1500)
        }

        @AfterClass @JvmStatic fun restoreWindow() {
            shell("wm size reset")
            shell("wm density reset")
        }
    }

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

    @org.junit.After fun removeTestSampleBooks() = runBlocking {
        val app = compose.activity.application as ReadAlongApplication
        app.database.bookDao().removeSamples()
        if (app.database.bookDao().metadata("current_book")?.startsWith("demo-") == true) app.database.bookDao().removeMetadata("current_book")
        Unit
    }

    private fun capture(name: String) {
        val app = compose.activity.application as ReadAlongApplication
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            java.io.File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun paragraphWidth(text: String): Int {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().size.width
    }

    @Test fun expandingThePreviewCardFillsTheTabletWidth() {
        val app = compose.activity.application as ReadAlongApplication
        val window = compose.activity.window.decorView
        val density = compose.activity.resources.displayMetrics.density
        assumeTablet(window.width / density, window.height / density)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Search library").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Search library").performClick()
        compose.onNodeWithText("Search title or author").performTextInput("Mira")
        compose.onNodeWithTag("book:demo-night-orchard").performClick()
        compose.waitUntil(5000) { app.playback.state.value.bookId == "demo-night-orchard" }
        compose.runOnUiThread { app.playback.pause(); app.playback.seek(20_000) }
        compose.waitUntil(5000) { app.playback.state.value.positionMs == 20_000L }
        compose.onNodeWithTag("flip_cover", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("follow_card", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        capture("verification-tablet-player.png")
        val card = compose.onNodeWithTag("follow_card", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val paragraph = compose.onNodeWithText("All day the branches", substring = true, useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        val previewWidth = paragraphWidth(paragraph)

        compose.onNodeWithContentDescription("Read in full screen").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("follow_passages").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        capture("verification-tablet-reader.png")
        val page = compose.onNodeWithTag("follow_passages").fetchSemanticsNode().boundsInRoot
        val readerWidth = paragraphWidth(paragraph)
        android.util.Log.i("ReadAlong", "Tablet reader width: window ${window.width}x${window.height}px, card ${card.width}px, " +
            "preview text ${previewWidth}px, page ${page.width}px, reader text ${readerWidth}px")
        assertTrue("The card is narrower than half the window on a tablet: ${card.width} of ${window.width}", card.width < window.width / 2f)
        // Default margins are a few dozen dp on each side; anything near the card's width is the bug.
        assertTrue("Reader text must use the page width (text $readerWidth px, page ${page.width} px, preview $previewWidth px)",
            readerWidth > page.width * 0.85f)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Read in full screen").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val back = compose.onNodeWithTag("follow_card", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("The card returns to its place", card.width, back.width, 2f)
        assertEquals(20_000L, app.playback.state.value.positionMs)
    }

    private fun assumeTablet(widthDp: Float, heightDp: Float) =
        org.junit.Assume.assumeTrue("Needs a landscape tablet window, got ${widthDp}x$heightDp dp", widthDp >= 1000f && widthDp > heightDp)
}
