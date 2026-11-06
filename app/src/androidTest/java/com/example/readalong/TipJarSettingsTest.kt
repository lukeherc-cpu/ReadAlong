package com.example.readalong

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
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

    @Test fun tipJarIsAQuietRowUnderAbout() {
        val original = runBlocking { app.appPreferences.preferences.first() }
        var light by mutableStateOf(true)
        var opened: String? = null
        try {
            compose.setContent {
                val settings by app.appPreferences.preferences.collectAsState(initial = original)
                CompositionLocalProvider(LocalAppSettings provides settings, LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened = uri }
                }) {
                    ReadAlongTheme(light) { SettingsScreen(app, {}, {}, {}) }
                }
            }
            // The tip jar no longer leads the page or has a setting of its own.
            compose.onNodeWithText("Move tip jar to Other settings").assertDoesNotExist()
            if (BuildConfig.TIP_JAR_ENABLED) {
                compose.onNodeWithTag("tip_jar_card").performScrollTo().assertIsDisplayed()
                if (BuildConfig.KOFI_URL.isNotBlank()) {
                    compose.onNodeWithTag("tip_jar_card").performClick()
                    compose.runOnIdle { assertEquals(BuildConfig.KOFI_URL, opened) }
                }
                capture("tip-jar-light")
                compose.runOnIdle { light = false }
                capture("tip-jar-dark")
            } else {
                compose.onNodeWithText("Tip jar").assertDoesNotExist()
            }
            compose.onNodeWithText("Offline speech models").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("About ReadAlong").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Other").assertDoesNotExist()
        } finally { runBlocking { app.appPreferences.update { original } } }
    }

    private fun capture(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            val directory = java.io.File(app.getExternalFilesDir(null), "verification/tip-jar").apply { mkdirs() }
            java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
