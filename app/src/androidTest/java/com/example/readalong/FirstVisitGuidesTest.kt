package com.example.readalong

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.data.preferences.AppPreferences
import com.example.readalong.ui.components.*
import com.example.readalong.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FirstVisitGuidesTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
    private fun capture(name: String) {
        compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap ->
            val folder = java.io.File(app.getExternalFilesDir(null), "verification/phase36").apply { mkdirs() }
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun allThreeGuidesUseBriefDescriptionsAndSuppressionIsCheckedByDefaultInBothThemes() {
        var page by mutableStateOf(GuidePage.HOME)
        var light by mutableStateOf(true)
        var choice: Boolean? = null
        compose.setContent { CompositionLocalProvider(LocalAppSettings provides AppPreferences(libraryLight = light)) {
            ReadAlongTheme(light) { Surface(Modifier.fillMaxSize()) { FirstVisitGuideDialog(page) { choice = it } } }
        } }
        for (theme in listOf(true, false)) {
            compose.runOnIdle { light = theme }
            for (guide in GuidePage.entries) {
                compose.runOnIdle { page = guide }
                compose.onNodeWithTag("guide_dont_show").assertIsOn()
                compose.onNodeWithText(guide.title).assertIsDisplayed()
                when (guide) {
                    GuidePage.HOME -> listOf("Add audiobooks", "Search", "Queue", "Library folders", "Settings")
                    GuidePage.PLAYER -> listOf("Cover art", "Create ReadAlong")
                    GuidePage.READER -> listOf("Settings", "Search", "Reading mode", "Chapters", "Back")
                }.forEach { compose.onNodeWithText(it).assertExists() }
                capture("guide-${guide.name.lowercase()}-${if (theme) "light" else "dark"}")
            }
        }
        compose.onNodeWithText("Do not show this again").performClick()
        compose.onNodeWithTag("guide_dont_show").assertIsOff()
        compose.onNodeWithText("Got it").performClick()
        compose.runOnIdle { assertEquals(false, choice) }
    }

    @Test fun checkedGuidesPersistIndependentlyAndUncheckedGuideReturnsOnAnotherVisit() {
        val original = runBlocking { app.appPreferences.preferences.first() }
        var page by mutableStateOf(GuidePage.HOME)
        var showing by mutableStateOf(true)
        try {
            runBlocking { app.appPreferences.update { it.copy(homeGuideHidden = false, playerGuideHidden = false, readerGuideHidden = false) } }
            compose.setContent {
                val settings by app.appPreferences.preferences.collectAsState(initial = original.copy(homeGuideHidden = false, playerGuideHidden = false, readerGuideHidden = false))
                CompositionLocalProvider(LocalAppSettings provides settings) { ReadAlongTheme {
                    if (showing) FirstVisitGuide(app, page, true)
                } }
            }
            compose.onNodeWithText("Got it").performClick()
            compose.waitUntil { runBlocking { app.appPreferences.preferences.first().homeGuideHidden } }
            compose.runOnIdle { showing = false }; compose.waitForIdle()
            compose.runOnIdle { showing = true }; compose.waitForIdle()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.runOnIdle { page = GuidePage.PLAYER }
            compose.onNodeWithText("Do not show this again").performClick()
            compose.onNodeWithText("Got it").performClick()
            assertFalse(runBlocking { app.appPreferences.preferences.first().playerGuideHidden })
            compose.runOnIdle { page = GuidePage.READER }
            compose.onNodeWithText("ReadAlong controls").assertIsDisplayed()
            compose.onNodeWithText("Got it").performClick()
            compose.waitUntil { runBlocking { app.appPreferences.preferences.first().readerGuideHidden } }
            compose.runOnIdle { page = GuidePage.PLAYER }
            compose.onNodeWithText("Your player").assertIsDisplayed()
            compose.onNodeWithTag("guide_dont_show").assertIsOn()
        } finally { runBlocking { app.appPreferences.update { original } } }
    }

    @Test fun chargingWarningTogglesByLabelAndSavesTheChoice() {
        val original = runBlocking { app.appPreferences.preferences.first() }
        var showing by mutableStateOf(true)
        try {
            compose.setContent { CompositionLocalProvider(LocalAppSettings provides original.copy(chargingOnly = false)) { ReadAlongTheme {
                if (showing) com.example.readalong.ui.transcription.TranscriptionBatteryWarning(app)
            } } }
            compose.onNodeWithText("Only transcribe while charging").performClick()
            compose.onNode(isToggleable()).assertIsOn()
            compose.onNodeWithText("Continue →").performClick()
            compose.waitUntil { runBlocking { app.appPreferences.preferences.first().chargingOnly } }
            assertTrue(runBlocking { app.appPreferences.preferences.first().batteryWarningSeen })
            compose.runOnIdle { showing = false }
        } finally { runBlocking { app.appPreferences.update { original } } }
    }

    @Test fun hidingGridMetadataKeepsCoverTitlesAndListModeRetainsLabels() {
        var show by mutableStateOf(true)
        var list by mutableStateOf(false)
        val book = com.example.readalong.data.book.DemoBooks.books.first().let {
            com.example.readalong.domain.model.Book(id = it.id, title = it.title, author = it.author, coverUri = it.coverUri, durationMs = it.durationMs, addedAt = it.addedAt, coverStyle = it.coverStyle, isDemo = it.isDemo, mediaState = it.mediaState)
        }
        compose.setContent { CompositionLocalProvider(LocalAppSettings provides AppPreferences(showTitleAndAuthor = show, listLibrary = list)) { ReadAlongTheme {
            com.example.readalong.ui.library.LibraryScreen(com.example.readalong.ui.library.LibraryUiState.Ready(listOf(book)), onRetry = {}, onAbout = {}, onBook = {})
        } } }
        compose.onNodeWithText(book.title).assertExists()
        compose.runOnIdle { show = false }
        compose.onNodeWithText(book.title).assertDoesNotExist()
        book.author?.let { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithTag("book:${book.id}").assertHasClickAction()
        compose.onNodeWithContentDescription("Open ${book.title}").assertExists()
        compose.runOnIdle { list = true }
        compose.onNodeWithText(book.title).assertExists()
        book.author?.let { compose.onNodeWithText(it.uppercase()).assertExists() }
    }
}
