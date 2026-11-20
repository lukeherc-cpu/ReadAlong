package com.example.readalong

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.domain.model.Book
import com.example.readalong.playback.PlaybackChapter
import com.example.readalong.playback.PlaybackControls
import com.example.readalong.playback.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SleepTimerSheetTest {
    @get:Rule val compose = createComposeRule()

    private class FakePlayback : PlaybackControls {
        val mutable = MutableStateFlow(PlaybackState(bookId = "one", positionMs = 200_000, durationMs = 3_600_000, isPlaying = true,
            chapters = listOf(PlaybackChapter(0, "Chapter 3", 0, 900_000))))
        override val state = mutable.asStateFlow()
        override fun select(book: Book) {}
        override fun pause() {}
        override fun play() {}
        override fun listenFrom(positionMs: Long) {}
        override fun toggle() {}
        override fun seek(positionMs: Long) {}
        override fun skip(deltaMs: Long) {}
        override fun speed(value: Float) {}
        override fun sleep(minutes: Int?) {
            mutable.value = mutable.value.copy(sleepEndsAt = minutes?.let { System.currentTimeMillis() + it * 60_000L }, sleepAtChapterEnd = false, sleepMinutes = minutes ?: 0)
        }
        override fun sleepMore(minutes: Int) { mutable.value.sleepEndsAt?.let { mutable.value = mutable.value.copy(sleepEndsAt = it + minutes * 60_000L) } }
        override fun sleepAtChapterEnd() { mutable.value = mutable.value.copy(sleepEndsAt = null, sleepAtChapterEnd = true, sleepMinutes = 0) }
        override fun chapter(index: Int) {}
    }

    private fun capture(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(600)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val folder = java.io.File(app.getExternalFilesDir(null), "verification/sleep-timer").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun pickingATimeThenAddingRestartingAndTurningOff() {
        val playback = FakePlayback()
        var open by mutableStateOf(true)
        compose.setContent {
            val palette = com.example.readalong.ui.components.CoverPalette(Color(0xFF2A1A12), Color(0xFFF3DDC0), Color(0xFFE8B57A))
            MaterialTheme(com.example.readalong.ui.theme.playerScheme(palette), typography = com.example.readalong.ui.theme.appTypography(com.example.readalong.ui.reading.readingFont("LITERATA"))) {
                Box(Modifier.fillMaxSize().background(Color(0xFF2B1814))) {
                    val state by playback.state.collectAsState()
                    if (open) com.example.readalong.ui.player.SleepTimerSheet(state, playback) { open = false }
                }
            }
        }
        // Not set: six time tiles and End of chapter, and no Turn off.
        compose.onNodeWithTag("sleep_30").assertExists()
        compose.onNodeWithTag("sleep_chapter").assertExists()
        compose.onNodeWithTag("sleep_off").assertDoesNotExist()
        capture("not-set")

        compose.onNodeWithTag("sleep_30").performClick()
        compose.runOnIdle { assertEquals(30, playback.state.value.sleepMinutes); assertFalse(open) }

        // Running: the countdown, add time, restart, the shake reminder.
        compose.runOnIdle { open = true }
        compose.onNodeWithText("Shake your phone to restart 30 min").assertExists()
        capture("running")
        val before = playback.state.value.sleepEndsAt!!
        compose.onNodeWithTag("sleep_add_5").performClick()
        compose.runOnIdle { assertEquals(before + 5 * 60_000L, playback.state.value.sleepEndsAt); assertEquals(30, playback.state.value.sleepMinutes) }
        compose.onNodeWithTag("sleep_restart").performClick()
        compose.runOnIdle { assertTrue(playback.state.value.sleepEndsAt!! - System.currentTimeMillis() in 29 * 60_000L..30 * 60_000L) }
        compose.onNodeWithTag("sleep_off").performClick()
        compose.runOnIdle { assertNull(playback.state.value.sleepEndsAt) }

        // End of chapter: the ring counts the chapter down, without +5 or the shake line.
        compose.onNodeWithTag("sleep_chapter").performClick()
        compose.runOnIdle { assertTrue(playback.state.value.sleepAtChapterEnd); open = true }
        compose.onNodeWithTag("sleep_add_5").assertDoesNotExist()
        compose.onNodeWithTag("sleep_off").assertExists()
        capture("chapter-end")
    }
}
