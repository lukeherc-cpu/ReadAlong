package com.example.readalong

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.domain.model.EqPreset
import com.example.readalong.domain.model.SoundSettings
import com.example.readalong.ui.about.SettingsScreen
import com.example.readalong.ui.sound.BookSoundSheet
import com.example.readalong.ui.theme.ReadAlongTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The equalizer in Settings (with Reset to default) and one book's own sound. */
class SoundSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<ReadAlongApplication>()

    @Test fun equalizerPresetSavesAndResetGoesFlat() {
        val original = runBlocking { app.sound.defaults.first() }
        try {
            runBlocking { app.sound.saveDefaults(SoundSettings.DEFAULT) }
            compose.setContent { ReadAlongTheme { SettingsScreen(app, {}, {}, {}) } }
            compose.onNodeWithTag("settings_sound").performScrollTo().performClick()
            compose.onNodeWithTag("sound_equalizer").performScrollTo().performClick()
            compose.onNodeWithTag("eq_preset_BRIGHTER").performClick()
            compose.waitUntil(5_000) { runBlocking { app.sound.defaults.first() }.let { it.equalizer && it.preset == EqPreset.BRIGHTER } }
            compose.onNodeWithTag("eq_reset").performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { app.sound.defaults.first() }.gains.all { it == 0 } }
        } finally { runBlocking { app.sound.saveDefaults(original) } }
    }

    @Test fun aBookKeepsItsOwnSoundUntilReset() {
        val book = "sound-test-${System.nanoTime()}"
        compose.setContent { ReadAlongTheme { BookSoundSheet(app, book, "Test book") {} } }
        compose.onNodeWithText("Your defaults").assertExists()
        compose.onNodeWithTag("sound_equalizer").performScrollTo().performClick()
        compose.onNodeWithTag("eq_preset_CAR").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { app.sound.own(book).first() }?.preset == EqPreset.CAR }
        // Closing the equalizer goes back to the Sound sheet, where "Use my defaults" now shows.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("sound_use_defaults").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sound_use_defaults").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { app.sound.own(book).first() } == null }
        assertNull(runBlocking { app.sound.own(book).first() })
    }
}
