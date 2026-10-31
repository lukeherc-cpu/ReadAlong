package com.example.readalong

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoundationNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @org.junit.Before fun dismissFirstUseSetup() = kotlinx.coroutines.runBlocking {
        val app = compose.activity.application as ReadAlongApplication
        app.speechModel.dismissSetup()
        app.appPreferences.update { com.example.readalong.data.preferences.AppPreferences(batteryWarningSeen = true, homeGuideHidden = true, playerGuideHidden = true, readerGuideHidden = true) }
        app.database.bookDao().putMetadata(com.example.readalong.data.local.LibraryMetadataEntity("battery_prompt_v1", "test"))
        app.bookRepository.observeBooks().first()
        app.database.bookDao().removeMetadata("hide_readalong_bar:demo-night-orchard")
        app.database.bookDao().insertBooks(com.example.readalong.data.book.DemoBooks.books)
        app.database.bookDao().insertProgress(com.example.readalong.data.book.DemoBooks.progress)
        Unit
    }

    @org.junit.After fun removeTestSampleBooks() = kotlinx.coroutines.runBlocking {
        val app = compose.activity.application as ReadAlongApplication
        app.database.bookDao().removeSamples()
        if (app.database.bookDao().metadata("current_book")?.startsWith("demo-") == true) app.database.bookDao().removeMetadata("current_book")
        Unit
    }

    private fun waitForLibrary() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Add audiobook").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun launchAboutAndReturnToLibrary() {
        waitForLibrary()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("About ReadAlong").performScrollTo().performClick()
        compose.onNodeWithText("Library preview").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to library").performClick()
        compose.onNodeWithText("Settings").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aboutDestinationSurvivesActivityRecreationAndSystemBack() {
        waitForLibrary()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("About ReadAlong").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("About ReadAlong").assertIsDisplayed()
        pressBack()
        pressBack()
        waitForLibrary()
    }

    @Test fun libraryUsesIconsAndLongPressExposesBookActions() {
        waitForLibrary()
        compose.onNodeWithText("Sample library").assertDoesNotExist()
        compose.onNodeWithText("Search title or author").assertDoesNotExist()
        compose.onNodeWithText("Recently played").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search library").assertIsDisplayed()
        compose.onNodeWithText("Transcription queue").assertDoesNotExist()
        compose.onNodeWithContentDescription("Audiobook folders").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add audiobook").performClick()
        compose.onNodeWithText("Add audiobooks").assertIsDisplayed()
        compose.onNodeWithText("Add audio file").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:demo-night-orchard"))
        compose.onNodeWithTag("book:demo-night-orchard").performTouchInput { longClick() }
        val order = listOf("Title", "Author", "Cover from file system", "Cover from internet", "Mark as not started", "Mark as finished", "Delete book")
        order.forEach { compose.onNodeWithText(it).assertExists() }
        val tops = order.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        org.junit.Assert.assertEquals(tops.sorted(), tops)
        compose.onNodeWithText("Mark as finished").performScrollTo().performClick()
        compose.waitUntil(5000) { kotlinx.coroutines.runBlocking { (compose.activity.application as ReadAlongApplication).bookRepository.observeBook("demo-night-orchard").first()?.isFinished == true } }

    }

    @Test fun readAlongMoreKeepsDiagnosticsWithoutDesktopTranscriptImport() {
        waitForLibrary()
        compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:demo-night-orchard"))
        compose.onNodeWithTag("book:demo-night-orchard").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Details", substring = false).assertDoesNotExist()
        compose.onNodeWithContentDescription("Hide ReadAlong bar").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Hide ReadAlong bar").assertDoesNotExist()
        compose.onNodeWithText("More", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Keep ReadAlong bar on player").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copy transcription diagnostics").assertExists()
        compose.onNodeWithText("Import transcript", substring = false).assertDoesNotExist()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            val app = compose.activity.application as ReadAlongApplication
            java.io.File(app.getExternalFilesDir(null), "verification-desktop-import-ui.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun miniPlayerExpandsThePlayerAndKeepsAudioPlayingEvenWithReadOnlyLibraryDefault() {
        val app = compose.activity.application as ReadAlongApplication
        val original = kotlinx.coroutines.runBlocking { app.appPreferences.preferences.first() }
        try {
            waitForLibrary()
            compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:demo-night-orchard"))
            compose.onNodeWithTag("book:demo-night-orchard").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isNotEmpty() }
            kotlinx.coroutines.runBlocking { app.appPreferences.update { it.copy(openingView = "READ_ONLY") } }
            compose.onNodeWithContentDescription("Collapse player").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Expand mini player").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnUiThread { app.playback.toggle() }
            compose.waitUntil(5000) { app.playback.state.value.isPlaying }
            compose.onNodeWithContentDescription("Expand mini player").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isNotEmpty() }
            org.junit.Assert.assertTrue("Expanding the mini-player must not enter silent book mode or pause playback", app.playback.state.value.isPlaying)
        } finally { compose.runOnUiThread { app.playback.pause() }; kotlinx.coroutines.runBlocking { app.appPreferences.update { original } } }
    }

    @Test fun swipingHomeMiniPlayerLeftStopsAudioAndDismissalSurvivesRecreation() {
        val app = compose.activity.application as ReadAlongApplication
        val id = "demo-night-orchard"
        waitForLibrary()
        compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:$id"))
        compose.onNodeWithTag("book:$id").performClick()
        compose.waitUntil(5000) { app.playback.state.value.bookId == id }
        compose.runOnUiThread { app.playback.seek(12000);app.playback.play() }
        compose.waitUntil(5000) { app.playback.state.value.isPlaying }
        compose.onNodeWithContentDescription("Collapse player").performClick()
        compose.onNodeWithContentDescription("Expand mini player").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { app.playback.state.value.bookId == null }
        org.junit.Assert.assertFalse(app.playback.state.value.isPlaying)
        compose.waitUntil(5000) { kotlinx.coroutines.runBlocking { app.database.bookDao().metadata("current_book") == null } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Expand mini player").assertDoesNotExist()
        val saved = kotlinx.coroutines.runBlocking { app.bookRepository.observeBook(id).first()!! }
        org.junit.Assert.assertTrue(saved.positionMs >= 12000)
        compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:$id"))
        compose.onNodeWithTag("book:$id").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isNotEmpty() }
        org.junit.Assert.assertTrue(app.playback.state.value.positionMs >= 12000)
    }

    @Test fun appearanceChangesWaitForRestartConfirmationAndCancelKeepsTheCurrentPalette() {
        val app = compose.activity.application as ReadAlongApplication
        val original = kotlinx.coroutines.runBlocking { app.appPreferences.preferences.first() }
        waitForLibrary()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Light theme").performClick()
        compose.onNodeWithText("Restart ReadAlong?").assertIsDisplayed()
        assertEquals(original.libraryLight, kotlinx.coroutines.runBlocking { app.appPreferences.preferences.first().libraryLight })
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Accent color").performClick()
        compose.onNodeWithText("Orange").performClick()
        compose.onNodeWithText("Set", substring = false).performClick()
        compose.onNodeWithText("Restart ReadAlong?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(original.basicColor, kotlinx.coroutines.runBlocking { app.appPreferences.preferences.first().basicColor })
    }

    @Test fun settingsSaveLibraryThemeOpeningViewAndSeparatePreviewFont() {
        val app = compose.activity.application as ReadAlongApplication
        val originalReader = kotlinx.coroutines.runBlocking { app.readerPreferences.preferences.first() }
        try {
            waitForLibrary()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithContentDescription("Light theme").performClick()
            compose.onNodeWithText("Restart ReadAlong?").assertIsDisplayed()
            compose.onNodeWithText("Restart").performClick()
            compose.onNodeWithContentDescription("Match mini player to book colors").performClick()
            compose.onNodeWithContentDescription("Follow along").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Open Read Along in full screen").performScrollTo().performClick()
            compose.onNodeWithText("Reading fonts and appearance").performScrollTo().performClick()
            compose.onNodeWithText("Preview font", substring = true).performClick()
            compose.onNodeWithText("Sans serif").performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithContentDescription("Light theme").performScrollTo().assertIsOff()
            compose.onNodeWithContentDescription("Match mini player to book colors").assertIsOff()
            compose.onNodeWithContentDescription("Open Read Along in full screen").performScrollTo().assertIsOn()
            kotlinx.coroutines.runBlocking {
                org.junit.Assert.assertEquals("SANS", app.readerPreferences.preferences.first().previewFont)
                org.junit.Assert.assertEquals("FULLSCREEN", app.appPreferences.preferences.first().openingView)
            }
        } finally {
            kotlinx.coroutines.runBlocking {
                app.appPreferences.update { com.example.readalong.data.preferences.AppPreferences(batteryWarningSeen = true, homeGuideHidden = true, playerGuideHidden = true, readerGuideHidden = true) }
                app.readerPreferences.save(originalReader)
            }
        }
    }

    @Test fun listModeAndBasicColorAndBackgroundChoicesSurviveRecreation() {
        val app = compose.activity.application as ReadAlongApplication
        try {
            waitForLibrary()
            compose.onNodeWithTag("library_sort").performScrollTo().performClick()
            compose.onNodeWithText("List mode").performClick()
            compose.waitUntil(5000) { kotlinx.coroutines.runBlocking { app.appPreferences.preferences.first().listLibrary } }
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> java.io.File(app.getExternalFilesDir(null), "verification-library-list.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            compose.onNodeWithContentDescription("Settings").performScrollTo().performClick()
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> java.io.File(app.getExternalFilesDir(null), "verification-modern-settings.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            compose.onNodeWithText("Accent color").performScrollTo().performClick()
            compose.onNodeWithText("Orange").performClick()
            compose.onNodeWithText("Set", substring = false).performClick()
            compose.onNodeWithText("Restart ReadAlong?").assertIsDisplayed()
            compose.onNodeWithText("Restart").performClick()
            compose.onNodeWithContentDescription("Always use basic color").performScrollTo().performClick()
            compose.onNodeWithText("Player background").performScrollTo().performClick()
            compose.onNodeWithText("Solid color").performClick()
            compose.activityRule.scenario.recreate()
            kotlinx.coroutines.runBlocking {
                val saved = app.appPreferences.preferences.first()
                org.junit.Assert.assertEquals(0xFFA76535, saved.basicColor)
                org.junit.Assert.assertTrue(saved.alwaysBasicColor)
                org.junit.Assert.assertFalse(saved.blurredPlayer)
                org.junit.Assert.assertTrue(saved.listLibrary)
            }
            compose.onNodeWithContentDescription("Always use basic color").performScrollTo().assertIsOn()
        } finally { kotlinx.coroutines.runBlocking { app.appPreferences.update { com.example.readalong.data.preferences.AppPreferences(batteryWarningSeen = true, homeGuideHidden = true, playerGuideHidden = true, readerGuideHidden = true) } } }
    }

    @Test
    fun searchByAuthorAndOpenBook() {
        waitForLibrary()
        compose.onNodeWithContentDescription("Search library").performClick()
            compose.onNodeWithText("Search title or author").performTextInput("Mira")
        compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:demo-night-orchard"))
        compose.onNodeWithTag("book:demo-night-orchard").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("The Night Orchard").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("The Night Orchard").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("Search title or author").assertExists()
    }

}
