package com.example.readalong

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.ui.about.SettingsScreen
import com.example.readalong.ui.theme.ReadAlongTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Rewind when resuming and the sleep timer's reset sound, set from Settings. */
class PlayerSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<ReadAlongApplication>()

    @Test fun resumeAndResetSoundSave() {
        val original = runBlocking { app.appPreferences.preferences.first() }
        try {
            runBlocking { app.appPreferences.update { it.copy(resumeRewind = "SMART", resetSound = "CHIME") } }
            compose.setContent { ReadAlongTheme { SettingsScreen(app, {}, {}, {}) } }
            compose.onNodeWithTag("settings_resume").performScrollTo().performClick()
            compose.onNodeWithTag("resume_SENTENCE").performClick()
            compose.onNodeWithText("OK").performClick()
            compose.waitUntil(5_000) { runBlocking { app.appPreferences.preferences.first() }.resumeRewind == "SENTENCE" }
            compose.onNodeWithText("Shake to reset").performScrollTo().assertExists()
            compose.onNodeWithTag("settings_reset_sound").performScrollTo().performClick()
            compose.onNodeWithText("Vibrate only").performClick()
            compose.waitUntil(5_000) { runBlocking { app.appPreferences.preferences.first() }.resetSound == "VIBRATE" }
        } finally {
            runBlocking { app.appPreferences.update { it.copy(resumeRewind = original.resumeRewind, resetSound = original.resetSound) } }
        }
    }
}
