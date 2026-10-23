package com.example.readalong

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import com.example.readalong.data.book.DemoBooks
import com.example.readalong.data.preferences.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercise the actual navigation and prompt priority rather than only standalone dialogs. */
class FirstVisitNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun setupSkipsModelPopupGuidesEachDestinationOnceAndCoverOnlySettingSurvivesRecreation() {
        val app = compose.activity.application as ReadAlongApplication
        val original = runBlocking { app.appPreferences.preferences.first() }
        val batteryPrompt = runBlocking { app.database.bookDao().metadata("battery_prompt_v1") }
        try {
            runBlocking {
                app.bookRepository.observeBooks().first()
                app.database.bookDao().insertBooks(DemoBooks.books)
                app.database.bookDao().insertProgress(DemoBooks.progress)
                app.database.bookDao().putMetadata(com.example.readalong.data.local.LibraryMetadataEntity("battery_prompt_v1", "test"))
                app.appPreferences.update { AppPreferences(setupStage = 0) }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Your audiobook library").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Later").performClick()
            compose.onNodeWithText("Offline transcription uses battery").assertIsDisplayed()
            compose.onNodeWithText("Offline Follow Along").assertDoesNotExist()
            compose.onNodeWithText("Continue →").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Your library").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("guide_dont_show").assertIsOn()
            compose.onNodeWithText("Got it").performClick()
            compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("book:demo-night-orchard"))
            compose.onNodeWithTag("book:demo-night-orchard").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Your player").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Got it").performClick()
            compose.onNodeWithTag("flip_cover").performClick()
            compose.onNodeWithContentDescription("Full screen Follow Along").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("ReadAlong controls").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Got it").performClick()
            compose.waitUntil { runBlocking { app.appPreferences.preferences.first().readerGuideHidden } }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("ReadAlong controls").assertDoesNotExist()
            pressBack(); pressBack()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("library_grid").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Your library").assertDoesNotExist()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Show title and author").performScrollTo().performClick()
            compose.waitUntil { runBlocking { !app.appPreferences.preferences.first().showTitleAndAuthor } }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Show title and author").performScrollTo().assertIsDisplayed()
            val saved = runBlocking { app.appPreferences.preferences.first() }
            assertFalse(saved.showTitleAndAuthor)
            assertTrue(saved.homeGuideHidden); assertTrue(saved.playerGuideHidden); assertTrue(saved.readerGuideHidden)
        } finally { runBlocking {
            app.appPreferences.update { original }
            if (batteryPrompt == null) app.database.bookDao().removeMetadata("battery_prompt_v1")
            else app.database.bookDao().putMetadata(com.example.readalong.data.local.LibraryMetadataEntity("battery_prompt_v1", batteryPrompt))
            app.database.bookDao().removeSamples()
        } }
    }
}
