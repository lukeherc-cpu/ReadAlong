package com.example.readalong

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.data.preferences.AppPreferences
import com.example.readalong.ui.theme.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReferenceDialogsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap ->
            java.io.File(app.getExternalFilesDir(null), "verification-reference-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun setupArtworkProceedsFromFolderDirectlyToBattery() {
        val store = androidx.lifecycle.ViewModelStore()
        val model = androidx.lifecycle.ViewModelProvider(store, com.example.readalong.ui.importing.ImportViewModel.factory(app.audioImporter, app.importedEpub, app.processing))[com.example.readalong.ui.importing.ImportViewModel::class.java]
        var page by mutableStateOf("folder")
        val original = runBlocking { app.appPreferences.preferences.first() }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalAppSettings provides AppPreferences(batteryWarningSeen = true)) {
                    ReadAlongTheme {
                        Surface(Modifier.fillMaxSize()) {
                            when (page) {
                                "folder" -> com.example.readalong.ui.importing.WelcomeScreen(model) { page = "background" }
                                "background" -> com.example.readalong.ui.transcription.BackgroundPreparationPrompt(app, force = true)
                            }
                        }
                    }
                }
            }
            compose.onNodeWithTag("welcome_next").performClick()
            compose.onNodeWithText("Choose folder").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Skip for now").performClick()
            compose.waitForIdle()
            capture("background")
            compose.onNodeWithText("Allow background work").assertIsDisplayed()
            compose.onNodeWithText("Not now").assertIsDisplayed()
        } finally { store.clear(); runBlocking { app.appPreferences.update { original } } }
    }

    @Test fun addAudiobookAndEditionChoicesUseIllustratedCards() {
        val store = androidx.lifecycle.ViewModelStore()
        val model = androidx.lifecycle.ViewModelProvider(store, com.example.readalong.ui.importing.ImportViewModel.factory(app.audioImporter, app.importedEpub, app.processing))[com.example.readalong.ui.importing.ImportViewModel::class.java]
        val uri = android.provider.DocumentsContract.buildDocumentUri("com.example.readalong.test.original-books", "book/Disc 1/Visual-${System.nanoTime()}.wav")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var book: String? = null
        try {
            automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
            app.contentResolver.call(uri, "grant-visual", android.provider.DocumentsContract.getDocumentId(uri), null)
            compose.setContent {
                CompositionLocalProvider(LocalAppSettings provides AppPreferences(batteryWarningSeen = true)) {
                    ReadAlongTheme {
                        Surface(Modifier.fillMaxSize()) {
                            com.example.readalong.ui.importing.ImportControls(model, {})
                            com.example.readalong.ui.importing.ImportPriorityPrompt(model)
                        }
                    }
                }
            }
            compose.onNodeWithContentDescription("Add audiobook").performClick()
            capture("add-audiobook")
            compose.onNodeWithText("Add audiobook").assertIsDisplayed()
            compose.onNodeWithText("Add one book").assertIsDisplayed()
            compose.onNodeWithText("Add audio file").assertIsDisplayed()
            compose.onNodeWithText("Import from Audible").assertIsDisplayed()
            compose.onNodeWithText("Manage library folders").assertIsDisplayed()
            // The sheet has no Cancel: Back (or a tap outside) closes it.
            androidx.test.espresso.Espresso.pressBack()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Add one book").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle { model.files(listOf(uri)) }
            compose.waitUntil(10000) { model.priority.value.isNotEmpty() || model.message.value?.contains("cannot keep access") == true }
            assertTrue(model.message.value, model.priority.value.isNotEmpty())
            book = model.priority.value.first().id
            capture("create-readalong")
            compose.onNodeWithText("Make a ReadAlong").assertIsDisplayed()
            compose.onNodeWithText("From the audio").performClick()
            compose.onAllNodesWithText("Make ReadAlong").onLast().assertIsEnabled()
            compose.onNodeWithText("Later").performClick()
            assertEquals("WAITING_SELECTION", runBlocking { app.database.processingDao().find(book!!)!!.state })
        } finally {
            store.clear()
            runBlocking { book?.let { app.processing.cancel(it); app.database.bookDao().removeBook(it) } }
            automation.dropShellPermissionIdentity()
        }
    }

    @Test fun chosenBasicColorsKeepTextContrastInBothModes() {
        var light by mutableStateOf(true)
        var key by mutableLongStateOf(0xFF496B8A)
        var contrast = 0.0
        var secondaryContrast = 0.0
        compose.setContent {
            CompositionLocalProvider(LocalAppSettings provides AppPreferences(basicColor = key)) {
                ReadAlongTheme(light) {
                    val colors = MaterialTheme.colorScheme
                    SideEffect {
                        contrast = androidx.core.graphics.ColorUtils.calculateContrast(colors.onBackground.toArgb(), colors.background.toArgb())
                        secondaryContrast = androidx.core.graphics.ColorUtils.calculateContrast(colors.onSurfaceVariant.toArgb(), colors.surfaceVariant.toArgb())
                    }
                }
            }
        }
        for (color in listOf(0xFF496B8A,0xFF159447,0xFFFF8800,0xFFDF112A,0xFF000000,0xFFFFFFFF)) for (mode in listOf(true,false)) {
            compose.runOnIdle { light = mode; key = color }
            compose.waitForIdle()
            assertTrue("Text contrast for $color light=$mode: $contrast", contrast >= 4.5)
            assertTrue("Secondary contrast for $color light=$mode: $secondaryContrast", secondaryContrast >= 4.5)
        }
    }
}
