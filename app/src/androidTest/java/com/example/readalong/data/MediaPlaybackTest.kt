package com.example.readalong.data

import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.MainActivity
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.data.local.AudioChapterEntity
import com.example.readalong.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class MediaPlaybackTest {
    @Test fun closingTheActivityStopsAudioAndKeepsItsListeningPosition() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val source = createTestWave(app, "Close-test-${System.nanoTime()}.wav", 10)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var id: String? = null
        try {
            id = app.audioImporter.importFiles(listOf(FileProvider.getUriForFile(app, "${app.packageName}.files", source)), false).bookIds.single()
            withContext(Dispatchers.Main) { app.playback.select(app.bookRepository.observeBook(id).first()!!) }
            assertTrue("Selected book: ${app.playback.state.value}", withTimeoutOrNull(5000) { while (app.playback.state.value.bookId != id) delay(50); true } == true)
            withContext(Dispatchers.Main) { app.playback.listenFrom(2000) }
            assertTrue("Playback started: ${app.playback.state.value}", withTimeoutOrNull(5000) { while (!app.playback.state.value.isPlaying) delay(50); true } == true)
            scenario.close()
            assertTrue("Playback stopped: ${app.playback.state.value}", withTimeoutOrNull(5000) { while (app.playback.state.value.isPlaying) delay(50); true } == true)
            assertTrue("Position saved: ${app.database.bookDao().progress(id)}; playback ${app.playback.state.value}", withTimeoutOrNull(5000) { while ((app.database.bookDao().progress(id)?.audioPositionMs ?: 0) < 2000) delay(50); true } == true)
            assertFalse(app.playback.state.value.isPlaying)
        } finally { scenario.close(); id?.let { app.playback.releaseBook(it); app.database.bookDao().removeBook(it) }; source.delete() }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun systemMediaButtonsUseCustomTimeJumpsAcrossFileBoundaries() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val settings = app.appPreferences.preferences.first()
        val waves = listOf(createTestWave(app, "System-media-${System.nanoTime()}-1.wav", 12), createTestWave(app, "System-media-${System.nanoTime()}-2.wav", 12))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val connection = PlaybackConnection(app)
        var id: String? = null
        try {
            app.appPreferences.update { it.copy(playerBack = 3, playerForward = 4) }
            id = app.audioImporter.importFiles(waves.map { FileProvider.getUriForFile(app, "${app.packageName}.files", it) }, false).bookIds.single()
            withContext(Dispatchers.Main) { connection.command(SELECT_BOOK, Bundle().apply { putString("bookId", id) }) }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { connection.controller().seekBackIncrement != 3000L }) delay(50) }
            withContext(Dispatchers.Main) {
                val controller = connection.controller()
                assertFalse(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
                assertFalse(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
                assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_BACK))
                assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_FORWARD))
                assertEquals(listOf(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD), controller.mediaButtonPreferences.map { it.playerCommand })
                connection.command(SEEK_BOOK, Bundle().apply { putLong("positionMs", 13_000) })
                controller.seekBack()
            }
            withTimeout(5000) { while (app.playbackStore.state.value.positionMs != 10_000L) delay(50) }
            withContext(Dispatchers.Main) { connection.controller().seekForward() }
            withTimeout(5000) { while (app.playbackStore.state.value.positionMs != 14_000L) delay(50) }
            app.appPreferences.update { it.copy(playerBack = 6, playerForward = 7) }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { connection.controller().seekForwardIncrement != 7000L }) delay(50) }
            withContext(Dispatchers.Main) { connection.controller().seekBack() }
            withTimeout(5000) { while (app.playbackStore.state.value.positionMs != 8000L) delay(50) }
            assertFalse(app.playbackStore.state.value.isPlaying)
        } finally {
            withContext(Dispatchers.Main) { connection.command(CLEAR_BOOK, Bundle.EMPTY) }
            id?.let { app.database.bookDao().removeBook(it) }; app.appPreferences.update { settings }
            waves.forEach { it.delete() }; scenario.close()
        }
    }

    @Test fun servicePlaysSeeksPersistsAndStopsAtChapterEndInBackground() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val previous = app.database.bookDao().metadata("current_book")
        val stamp = System.nanoTime()
        val fixtures = listOf(createTestWave(app, "Playback-$stamp-1.wav", 4), createTestWave(app, "Playback-$stamp-2.wav", 8))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val connection = PlaybackConnection(app)
        var id: String? = null
        try {
            val uris = fixtures.map { FileProvider.getUriForFile(app, "${app.packageName}.files", it) }
            id = app.audioImporter.importFiles(uris, persistAccess = false).bookIds.single()
            val secondFile = app.database.mediaDao().audioFiles(id)[1]
            app.database.mediaDao().putChapters(listOf(
                AudioChapterEntity("$id-inner-1", secondFile.id, "Inner chapter one", 0, 0, 4000),
                AudioChapterEntity("$id-inner-2", secondFile.id, "Inner chapter two", 1, 4000, 8000)))
            withContext(Dispatchers.Main) {
                assertEquals(0, connection.command("readalong.SELECT_BOOK", Bundle().apply { putString("bookId", id) }).resultCode)
                connection.controller().play()
            }
            withTimeout(10_000) { while (!app.playbackStore.state.value.isPlaying) delay(100) }
            assertEquals(id, app.playbackStore.state.value.bookId)
            scenario.moveToState(Lifecycle.State.CREATED)
            val before = app.playbackStore.state.value.positionMs
            withTimeout(5_000) { while (app.playbackStore.state.value.positionMs < before + 500) delay(100) }
            withContext(Dispatchers.Main) {
                connection.command("readalong.SEEK_BOOK", Bundle().apply { putLong("positionMs", 6_500) })
                connection.controller().setPlaybackSpeed(1.25f)
                connection.controller().pause()
            }
            withTimeout(5_000) { while (app.database.bookDao().progress(id)?.audioPositionMs?.let { it < 6_400 } != false) delay(100) }
            withTimeout(5_000) { while (app.database.bookDao().metadata("playback_speed") != "1.25") delay(100) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(1, app.playbackStore.state.value.chapterIndex)
            assertEquals("1.25", app.database.bookDao().metadata("playback_speed"))
            withContext(Dispatchers.Main) {
                connection.command("readalong.SEEK_BOOK", Bundle().apply { putLong("positionMs", 3_500) })
                connection.command("readalong.SLEEP_TIMER", Bundle().apply { putBoolean("chapterEnd", true) })
                connection.controller().play()
            }
            withTimeout(8_000) { while (app.playbackStore.state.value.sleepAtChapterEnd) delay(100) }
            assertFalse(app.playbackStore.state.value.isPlaying)
            assertEquals(1, app.playbackStore.state.value.chapterIndex)
            withContext(Dispatchers.Main) {
                connection.command("readalong.SEEK_CHAPTER", Bundle().apply { putInt("chapterIndex", 2) })
            }
            assertEquals(2, app.playbackStore.state.value.chapterIndex)
            assertEquals(8000L, app.playbackStore.state.value.positionMs)
            assertEquals(0L, app.playbackStore.state.value.chapterPositionMs)
            assertEquals(secondFile.id, app.database.bookDao().metadata("file:$id"))
            withContext(Dispatchers.Main) {
                connection.command("readalong.SEEK_BOOK", Bundle().apply { putLong("positionMs", 7500); putBoolean("play", true) })
                connection.command("readalong.SLEEP_TIMER", Bundle().apply { putBoolean("chapterEnd", true) })
                assertTrue(connection.controller().playWhenReady)
            }
            withTimeout(8000) { while (app.playbackStore.state.value.sleepAtChapterEnd) delay(100) }
            assertFalse(app.playbackStore.state.value.isPlaying)
            assertEquals(8000L, app.playbackStore.state.value.positionMs)
            assertEquals(2, app.playbackStore.state.value.chapterIndex)
            withContext(Dispatchers.Main) { assertEquals(1, connection.controller().currentMediaItemIndex) }
        } finally {
            withContext(Dispatchers.Main) { connection.command(CLEAR_BOOK, Bundle.EMPTY) }
            delay(500)
            id?.let { app.database.bookDao().removeBook(it) }
            app.database.bookDao().putMetadata(LibraryMetadataEntity("current_book", previous.orEmpty()))
            fixtures.forEach { it.delete() }
            scenario.close()
        }
    }
}
