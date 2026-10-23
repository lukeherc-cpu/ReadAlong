package com.example.readalong

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.data.epub.GeneratedEpubRepository
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.data.processing.ProcessingRepository
import com.example.readalong.data.reading.TranscriptRepository
import com.example.readalong.playback.PlaybackState
import com.example.readalong.ui.player.PlayerScreen
import com.example.readalong.ui.reading.ReadingViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerBufferingTest {
    @get:Rule val compose = createComposeRule()
    @Test fun shortBuffersDoNotFlashAndLongBuffersDoNotMoveTheControls() {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val book = com.example.readalong.data.book.DemoBooks.books.first()
        runBlocking { db.bookDao().insertBooks(listOf(book)) }
        val model = ReadingViewModel(book.id, db, TranscriptRepository(db), app.readerPreferences,
            GeneratedEpubRepository(app, db), ProcessingRepository(app, db, app.speechModel))
        val store = androidx.lifecycle.ViewModelStore().apply { put("reader", model) }
        val loading = mutableStateOf(false)
        try {
            compose.setContent { MaterialTheme { PlayerScreen(com.example.readalong.data.local.BookWithProgress(book, null).toBook(), PlaybackState(bookId = book.id, durationMs = 60_000, isLoading = loading.value), app.playback, {}, {}, model, {}, {}) } }
            compose.waitForIdle()
            val before = compose.onNodeWithContentDescription("Seek within chapter").fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { loading.value = true }
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithContentDescription("Buffering audio").assertDoesNotExist()
            compose.runOnUiThread { loading.value = false }
            compose.mainClock.advanceTimeBy(700)
            compose.onNodeWithContentDescription("Buffering audio").assertDoesNotExist()
            compose.runOnUiThread { loading.value = true }
            compose.mainClock.advanceTimeBy(800)
            compose.onNodeWithContentDescription("Buffering audio").assertExists()
            assertEquals(before, compose.onNodeWithContentDescription("Seek within chapter").fetchSemanticsNode().boundsInRoot)
            compose.runOnUiThread { loading.value = false }
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithContentDescription("Buffering audio").assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true; compose.runOnIdle { store.clear() }; db.close() }
    }
}
