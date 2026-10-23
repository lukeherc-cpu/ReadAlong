package com.example.readalong

import com.example.readalong.data.reading.toEntity

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.example.readalong.data.preferences.ReaderPreferences
import com.example.readalong.ui.reading.ReaderAppearance
import com.example.readalong.ui.reading.readingHighlight
import com.example.readalong.data.transcription.ModelStatus
import com.example.readalong.domain.transcription.SpeechModels
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadingPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun coverLineAndSquareUsePlaybackSentenceAndClearEmphasisDuringMusic() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, com.example.readalong.data.local.ReadAlongDatabase::class.java).build()
        val id = "shared-preview-clock"
        val text = "The gate opened. Then it closed."
        kotlinx.coroutines.runBlocking {
            db.bookDao().insertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false)))
            db.readingDao().putSegments(listOf(com.example.readalong.domain.model.TranscriptSegment("preview-clock", id, "one", 1000, 7500, text,
                words = listOf("The", "gate", "opened.", "Then", "it", "closed.").mapIndexed { i, word ->
                    val start = if (i < 3) 1000L + i * 500 else 6000L + (i - 3) * 500
                    com.example.readalong.domain.model.TranscriptWord(word, start, start + 400)
                }).toEntity()))
        }
        val model = com.example.readalong.ui.reading.ReadingViewModel(id, db, com.example.readalong.data.reading.TranscriptRepository(db),
            app.readerPreferences, com.example.readalong.data.epub.GeneratedEpubRepository(app, db),
            com.example.readalong.data.processing.ProcessingRepository(app, db, app.speechModel))
        val store = androidx.lifecycle.ViewModelStore().apply { put("reader", model) }
        val position = androidx.compose.runtime.mutableLongStateOf(1500)
        try {
            compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Column {
                val state = com.example.readalong.playback.PlaybackState(bookId = id, positionMs = position.longValue)
                com.example.readalong.ui.reading.ActiveSentence(state, model)
                com.example.readalong.ui.reading.FollowCard(state, model, {}, color = Color(0xFF182A3B))
            } } }
            fun line() = compose.onNodeWithTag("active_sentence").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
            fun emphasized(sentence: String): Boolean {
                val painted = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("follow_preview")), useUnmergedTree = true)
                    .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
                val start = text.indexOf(sentence)
                return painted.spanStyles.any { it.start == start && it.end == start + sentence.length && it.item.color == Color.White }
            }
            compose.waitUntil(10_000) { line() == "The gate opened." }
            assertTrue(emphasized("The gate opened."))
            compose.runOnIdle { position.longValue = 6500 }
            compose.waitUntil(5000) { line() == "Then it closed." }
            assertTrue(emphasized("Then it closed.")); assertFalse(emphasized("The gate opened."))
            compose.runOnIdle { position.longValue = 4000 }
            compose.waitUntil(5000) { line().isEmpty() }
            assertFalse(emphasized("The gate opened.")); assertFalse(emphasized("Then it closed."))
        } finally { compose.runOnIdle { store.clear() }; db.close() }
    }

    @Test fun estimatedPassageScrollsContinuouslyWithinItsBoundedInterval() {
        val cue = com.example.readalong.domain.reading.SentenceCue(10, 110, 3000, 7000, estimated = true)
        val positions = listOf(3000L,4000L,5000L,6000L,7000L).map {
            com.example.readalong.ui.reading.spokenCharacter(emptyList(),cue,it)
        }
        assertEquals(listOf(10,35,60,85,109), positions)
    }

    @Test fun playbackEmphasisPreservesLineBreaksAndEveryCharacterPosition() {
        var checked = false
        compose.setContent {
            val measurer = rememberTextMeasurer()
            val words = "The orchard leaves rustled beside the river. A traveler crossed the old bridge and watched the morning sky."
            for (size in listOf(16, 24, 32)) {
                val original = buildAnnotatedString {
                    append(words)
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), 12, 30)
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace), 55, 75)
                }
                val style = TextStyle(fontFamily = FontFamily.Serif, fontSize = size.sp, lineHeight = (size * 1.6f).sp)
                val baseline = measurer.measure(original, style, constraints = Constraints(maxWidth = 340))
                for (range in listOf(0..11, 12..29, 55..74, 85..100)) {
                    val highlighted = buildAnnotatedString { append(original); addStyle(readingHighlight(Color.Green), range.first, range.last + 1) }
                    val current = measurer.measure(highlighted, style, constraints = Constraints(maxWidth = 340))
                    assertEquals(baseline.size, current.size)
                    assertEquals(baseline.lineCount, current.lineCount)
                    repeat(baseline.lineCount) { line ->
                        assertEquals(baseline.getLineStart(line), current.getLineStart(line))
                        assertEquals(baseline.getLineEnd(line), current.getLineEnd(line))
                    }
                    words.indices.forEach { assertEquals(baseline.getBoundingBox(it), current.getBoundingBox(it)) }
                }
            }
            checked = true
        }
        compose.runOnIdle { assertTrue(checked) }
    }

    @Test fun previewScrollsWithinOneLongSentenceWhileKeepingSentenceHighlight() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, com.example.readalong.data.local.ReadAlongDatabase::class.java).build()
        val id = "preview-long-sentence"
        val tokens = (0 until 120).map { "word$it" }
        val text = tokens.joinToString(" ") + "."
        kotlinx.coroutines.runBlocking {
            db.bookDao().insertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false)))
            db.readingDao().putSegments(listOf(com.example.readalong.domain.model.TranscriptSegment("long-sentence", id, "one", 0, 12_000, text,
                words = tokens.mapIndexed { i, token -> com.example.readalong.domain.model.TranscriptWord(token, i * 100L, i * 100L + 90) }).toEntity()))
        }
        val model = com.example.readalong.ui.reading.ReadingViewModel(id, db, com.example.readalong.data.reading.TranscriptRepository(db),
            app.readerPreferences, com.example.readalong.data.epub.GeneratedEpubRepository(app, db),
            com.example.readalong.data.processing.ProcessingRepository(app, db, app.speechModel))
        val store = androidx.lifecycle.ViewModelStore().apply { put("reader", model) }
        val position = androidx.compose.runtime.mutableLongStateOf(150L)
        try {
            compose.setContent { MaterialTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.requiredWidth(260.dp)) {
                    com.example.readalong.ui.reading.FollowCard(com.example.readalong.playback.PlaybackState(bookId = id, positionMs = position.longValue), model, {}, color = Color(0xFF182A3B))
                }
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            fun scroll() = compose.onNodeWithTag("follow_preview", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            val before = scroll()
            compose.runOnIdle { position.longValue = 10_550L }
            compose.waitUntil(10_000) { scroll() > before + 100f }
            val painted = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
            assertTrue(painted.spanStyles.any { it.start == 0 && it.end == text.length && it.item.color == androidx.compose.ui.graphics.Color.White })
        } finally { compose.runOnIdle { store.clear() }; db.close() }
    }

    @Test fun longPreviewRetainsItsExcerptThroughAnAudioGap() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, com.example.readalong.data.local.ReadAlongDatabase::class.java).build()
        val id = "preview-gap"
        val prefix = "Earlier. ".repeat(900)
        val text = prefix + "The lantern glowed beside the gate."
        kotlinx.coroutines.runBlocking {
            db.bookDao().insertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false)))
            db.importedEpubDao().put(com.example.readalong.data.local.ImportedEpubEntity(id, "gap", "", "Original", "ALIGNED", "Matched", "EPUB", 0, 1, 1, 1, 1, 0))
            val anchors = org.json.JSONArray().put(org.json.JSONObject().put("id", "original").put("start", prefix.length).put("end", text.length).put("startMs", 500).put("endMs", 1200)).toString()
            db.importedEpubDao().putBlocks(listOf(com.example.readalong.data.local.ImportedEpubBlockEntity(id, "gap", 0, 0, "chapter", text, "P", "[]", false, anchors, 500, 1200, 1f)))
        }
        val model = com.example.readalong.ui.reading.ReadingViewModel(id, db, com.example.readalong.data.reading.TranscriptRepository(db),
            app.readerPreferences, com.example.readalong.data.epub.GeneratedEpubRepository(app, db),
            com.example.readalong.data.processing.ProcessingRepository(app, db, app.speechModel),
            com.example.readalong.data.epub.ImportedEpubRepository(app, db))
        val store = androidx.lifecycle.ViewModelStore().apply { put("reader", model) }
        val position = androidx.compose.runtime.mutableLongStateOf(700L)
        try {
            compose.setContent { MaterialTheme { com.example.readalong.ui.reading.FollowCard(com.example.readalong.playback.PlaybackState(bookId = id, positionMs = position.longValue), model, {}, color = Color(0xFF182A3B)) } }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("The lantern", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            val before = compose.onNodeWithTag("follow_preview", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            compose.runOnIdle { position.longValue = 1400L }
            compose.waitForIdle()
            val after = compose.onNodeWithTag("follow_preview", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            assertEquals("A timing gap must not reset the preview scroll", before, after, .1f)
            val card = compose.onNodeWithTag("follow_card").fetchSemanticsNode().boundsInRoot
            assertEquals(card.width, card.height, 2f)
        } finally { compose.runOnIdle { store.clear() }; db.close() }
    }

    @Test fun underCoverPhraseFitsAndAdvancesWithoutCuttingWords() {
        val sentence = "A silver lantern shone beside the quiet river while the traveler waited for the morning train."
        val position = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent { MaterialTheme {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.then(androidx.compose.ui.Modifier.requiredWidth(180.dp))) {
                com.example.readalong.ui.reading.FittedSentenceLine(sentence, position.intValue)
            }
        } }
        compose.waitForIdle()
        fun displayed(): String = compose.onNodeWithTag("active_sentence").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        val initial = displayed()
        assertTrue(initial.startsWith("A silver")); assertTrue(initial.length < sentence.length)
        compose.runOnIdle { position.intValue = sentence.indexOf("morning") }
        val later = displayed()
        assertNotEquals(initial, later); assertTrue(later.contains("morning"))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("active_sentence").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        assertFalse(later.contains('…'))
        assertTrue(sentence.contains(later))
    }

    @Test fun readerFontSizeAppliesIndependentlyOfEpubFonts() {
        var saved: ReaderPreferences? = null
        compose.setContent { MaterialTheme { ReaderAppearance(ReaderPreferences(), {}, onSave = { saved = it }) } }
        compose.onNodeWithTag("reader_font_size").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(28f) }
        compose.onNodeWithText("Font size · 28").assertExists()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(28f, saved!!.fontSize); assertTrue(saved!!.useEpubFonts) }
    }

    @Test fun offlineSpeechModelSettingsRemainAvailableWithoutASetupPopup() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val model = androidx.lifecycle.ViewModelProvider(store, com.example.readalong.ui.transcription.ModelViewModel.factory(app, app.speechModel, app.processing))[com.example.readalong.ui.transcription.ModelViewModel::class.java]
            compose.setContent { MaterialTheme { com.example.readalong.ui.transcription.ModelScreen(model) {} } }
            compose.onNodeWithText("Offline speech model").assertExists()
            compose.onNodeWithText("Cactus Whistle · 17 MB").assertExists()
            compose.onNodeWithText("Offline Follow Along").assertDoesNotExist()
            compose.onNode(isDialog()).assertDoesNotExist()
        } finally { store.clear() }
    }
}
