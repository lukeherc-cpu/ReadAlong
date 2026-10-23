package com.example.readalong

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.data.epub.GeneratedEpubRepository
import com.example.readalong.data.local.*
import com.example.readalong.data.preferences.ReaderPreferences
import com.example.readalong.data.processing.ProcessingRepository
import com.example.readalong.data.reading.TranscriptRepository
import com.example.readalong.domain.model.Book
import com.example.readalong.playback.*
import com.example.readalong.ui.reading.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercise real Paging, text layout, controls and mode-specific typography. */
class ReaderModePositionTest {
    @get:Rule val compose = createComposeRule()
    private var readingInsetPx = 0f

    private class Player(id: String) : PlaybackControls {
        val mutable = MutableStateFlow(PlaybackState(bookId = id, positionMs = 20_000, durationMs = 240_000))
        override val state = mutable.asStateFlow()
        override fun select(book: Book) = Unit
        override fun pause() { mutable.value = mutable.value.copy(isPlaying = false) }
        override fun play() { mutable.value = mutable.value.copy(isPlaying = true) }
        override fun seek(positionMs: Long) { mutable.value = mutable.value.copy(positionMs = positionMs) }
        override fun listenFrom(positionMs: Long) { seek(positionMs); play() }
        override fun toggle() { if (state.value.isPlaying) pause() else play() }
        override fun skip(deltaMs: Long) { seek(state.value.positionMs + deltaMs) }
        override fun speed(value: Float) = Unit
        override fun sleep(minutes: Int?) = Unit
        override fun sleepAtChapterEnd() = Unit
        override fun chapter(index: Int) = Unit
    }

    private fun reveal() {
        if (compose.onAllNodesWithContentDescription("Read only").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("reader_reveal_controls").performTouchInput { click(Offset(8f, 8f)) }
        }
    }

    private data class Line(val text: String, val start: Int, val end: Int)
    private fun visibleLine(): Line {
        // The shared 64 dp reading inset is below the overlaid toolbar in either mode.
        val top = compose.onNodeWithTag("follow_passages").fetchSemanticsNode().boundsInRoot.top + readingInsetPx
        val paragraphs = compose.onAllNodesWithTag("reader_paragraph").fetchSemanticsNodes()
        val node = paragraphs.first { it.boundsInRoot.bottom > top && it.boundsInRoot.height > 0 }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.runOnIdle { node.config[SemanticsActions.GetTextLayoutResult].action!!(layouts) }
        val layout = layouts.single()
        val line = layout.getLineForVerticalPosition((top - node.positionInRoot.y).coerceAtLeast(0f))
        return Line(layout.layoutInput.text.text, layout.getLineStart(line), layout.getLineEnd(line))
    }

    @Test fun switchingKeepsVisibleTextAcrossChaptersDifferentFontsAndUnmatchedText() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, ReadAlongDatabase::class.java).build()
        val store = ViewModelStore()
        val originalFollow = app.readerPreferences.preferences.first()
        val originalPlain = app.bookReaderPreferences.preferences.first()
        try {
            val id = "switch-visible-line"
            val book = com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false)
            db.bookDao().upsertBooks(listOf(book))
            val text = "Across the river the reader finds a different place. "
            val blocks = (0..239).map { n -> ImportedEpubBlockEntity(id, "visible-edition", n, n / 40, "chapter-${n / 40}",
                "Passage $n. " + (0..25).joinToString(" ") { "$it $text" }, "P", "[]", false, "[]",
                if (n < 160) n * 1000L else -1L, if (n < 160) (n + 1) * 1000L else -1L, 1f) }
            db.importedEpubDao().put(ImportedEpubEntity(id, "visible-edition", "", "Original", "ALIGNED", "", "EPUB", 0, 240, 160, 160, 240, 0))
            db.importedEpubDao().putBlocks(blocks)
            // One confirmed source anchor exercises audio handoff as well as the viewport.
            db.readingDao().putSegments(listOf(TranscriptEntity("source-70", id, "chapter-1", 70_000, 71_000, blocks[70].text, 1f, null)))
            db.importedEpubDao().putBlocks(listOf(blocks[70].copy(anchorsJson =
                """[{"id":"source-70","start":0,"end":${blocks[70].text.length},"startMs":70000,"endMs":71000}]""")))
            db.bookDao().putMetadata(LibraryMetadataEntity("book_viewport:$id",
                """{"edition":"visible-edition","index":180,"id":"epub:visible-edition:180","character":600}"""))
            app.readerPreferences.save(ReaderPreferences(fontSize = 18f, fontFamily = "LORA", lineSpacing = 1.4f))
            app.bookReaderPreferences.save(ReaderPreferences(fontSize = 24f, fontFamily = "EPUB", margin = 36f, lineSpacing = 1.8f, theme = "LIGHT"))
            val model = ReadingViewModel(id, db, TranscriptRepository(db), app.readerPreferences,
                GeneratedEpubRepository(app, db), ProcessingRepository(app, db, app.speechModel),
                bookPreferencesStore = app.bookReaderPreferences, initialReadOnly = true)
            store.put("reader", model)
            val player = Player(id)
            model.openReader(20_000)
            withTimeout(5000) { model.readerStart.first { it != null } }
            compose.setContent {
                val state by player.state.collectAsState()
                val density = androidx.compose.ui.platform.LocalDensity.current
                readingInsetPx = with(density) { 64.dp.toPx() } + WindowInsets.displayCutout.getTop(density)
                Surface(Modifier.fillMaxSize()) { FollowScreen(Book(id, "Mode position test"), state, player, model, {}) }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Passage 180.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val before = visibleLine()
            assertTrue(before.text.startsWith("Passage 180."))
            assertTrue(before.start > 0)
            val pager = model.readerStart.value!!.pagerGeneration
            reveal()
            compose.onNodeWithContentDescription("ReadAlong").performClick()
            compose.waitUntil(5000) { !model.readOnly.value }
            compose.waitForIdle()
            val after = visibleLine()
            assertEquals("Must not jump back to chapter 2 audio", before.text, after.text)
            assertTrue("The same character must stay in the first visible line: ${before.start} -> ${after.start}..${after.end}; target=${model.readerStart.value}", before.start in after.start..after.end)
            assertEquals(pager, model.readerStart.value!!.pagerGeneration)
            assertEquals("No matching audio exists here, so don't invent a seek", 20_000L, player.state.value.positionMs)
            compose.onNodeWithText("Loading passage…").assertDoesNotExist()
            // Move in ReadAlong independently, then switch back: the earlier read-only
            // chapter 5 bookmark must not replace this newly visible chapter 2 passage.
            compose.onNodeWithTag("follow_passages").performScrollToIndex(70)
            compose.onNodeWithTag("follow_passages").performTouchInput { swipeUp(startY = height * .62f, endY = height * .35f, durationMillis = 500) }
            compose.waitForIdle()
            val second = visibleLine()
            assertTrue(second.text.startsWith("Passage 70."))
            reveal()
            compose.onNodeWithContentDescription("Read only").performClick()
            compose.waitUntil(5000) { model.readOnly.value }
            compose.waitForIdle()
            val returned = visibleLine()
            assertEquals(second.text, returned.text)
            assertTrue("Same character after switching to larger plain-reading font", second.start in returned.start..returned.end)
            assertEquals(pager, model.readerStart.value!!.pagerGeneration)
            assertFalse(player.state.value.isPlaying)
            // Same font size but different family/margins must wait for the new layout too.
            app.bookReaderPreferences.save(ReaderPreferences(fontSize = 18f, fontFamily = "MONO", margin = 40f, lineSpacing = 1.4f, theme = "LIGHT"))
            compose.waitUntil(5000) { model.bookPreferences.value.fontFamily == "MONO" }
            compose.waitForIdle()
            val sameSize = visibleLine()
            reveal()
            compose.onNodeWithContentDescription("ReadAlong").performClick()
            compose.waitUntil(5000) { !model.readOnly.value && player.state.value.positionMs in 69_800..71_000 }
            compose.waitForIdle()
            val sameSizeAfter = visibleLine()
            assertEquals(sameSize.text, sameSizeAfter.text)
            assertTrue("Same-size font/margin changes must keep the character", sameSize.start in sameSizeAfter.start..sameSizeAfter.end)
            assertFalse(player.state.value.isPlaying)
            val output = java.io.File(app.getExternalFilesDir(null), "verification-reader-mode-sync.png")
            val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            output.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            db.close()
            app.readerPreferences.save(originalFollow)
            app.bookReaderPreferences.save(originalPlain)
        }
    }

    @Test fun missingPassagesUseASkeletonRatherThanLoadingWords() {
        compose.setContent { MaterialTheme { PassageSkeleton() } }
        compose.onNodeWithTag("passage_skeleton").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading reading text").assertExists()
        compose.onNodeWithText("Loading passage", substring = true).assertDoesNotExist()
    }
}
