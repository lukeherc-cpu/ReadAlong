package com.example.readalong

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.data.preferences.AppPreferencesStore
import com.example.readalong.ui.about.SettingsScreen
import com.example.readalong.ui.theme.LocalAppSettings
import com.example.readalong.ui.theme.ReadAlongTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TipJarSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<ReadAlongApplication>()

    @Test fun tipJarMovesInBothDirectionsPersistsAndOtherStaysExpanded() {
        val original = runBlocking { app.appPreferences.preferences.first() }
        var light by mutableStateOf(true)
        var opened: String? = null
        try {
            runBlocking { app.appPreferences.update { it.copy(tipJarInOther = false) } }
            compose.setContent {
                val settings by app.appPreferences.preferences.collectAsState(initial = original.copy(tipJarInOther = false))
                CompositionLocalProvider(LocalAppSettings provides settings, LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened = uri }
                }) {
                    ReadAlongTheme(light) { SettingsScreen(app, {}, {}, {}) }
                }
            }
            if (BuildConfig.TIP_JAR_ENABLED) {
                compose.onNodeWithTag("tip_jar_top").assertExists()
                compose.onNodeWithTag("tip_jar_move").assertIsOff()
                if (BuildConfig.KOFI_URL.isNotBlank()) {
                    compose.onNodeWithTag("tip_jar_card").performClick()
                    compose.runOnIdle { assertEquals(BuildConfig.KOFI_URL, opened) }
                }
                capture("tip-jar-light-top")
                compose.runOnIdle { light = false }
                capture("tip-jar-dark-top")
                compose.onNodeWithText("Move tip jar to Other settings").performClick()
                compose.waitUntil { runBlocking { AppPreferencesStore(app).preferences.first().tipJarInOther } }
                compose.onNodeWithTag("tip_jar_top").assertDoesNotExist()
                compose.onNodeWithTag("tip_jar_other").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Offline speech models").assertExists()
                compose.onNodeWithTag("tip_jar_move").assertIsOn()
                capture("tip-jar-dark-other")
                compose.onNodeWithText("Move tip jar to Other settings").performClick()
                compose.waitUntil { !runBlocking { AppPreferencesStore(app).preferences.first().tipJarInOther } }
                compose.onNodeWithTag("tip_jar_other").assertDoesNotExist()
                compose.onNodeWithTag("tip_jar_top").performScrollTo().assertIsDisplayed()
            } else {
                compose.onNodeWithText("Tip jar").assertDoesNotExist()
                compose.onNodeWithTag("tip_jar_move").assertDoesNotExist()
            }
            compose.onNodeWithText("Offline speech models").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("About ReadAlong").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Other").assertDoesNotExist() // Only the OTHER section label remains.
        } finally { runBlocking { app.appPreferences.update { original } } }
    }

    private fun capture(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            val directory = java.io.File(app.getExternalFilesDir(null), "verification/tip-jar").apply { mkdirs() }
            java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
