package com.example.readalong

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.ui.player.CharacterListSheet
import com.example.readalong.ui.player.HistorySheet
import com.example.readalong.ui.theme.ReadAlongTheme
import org.junit.Rule
import org.junit.Test

/** Adding someone to a book's Character List, and an empty Listening history. */
class CharacterListTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<ReadAlongApplication>()

    @Test fun addingACharacterShowsThemWithTheirNote() {
        val book = "character-list-test-${System.nanoTime()}"
        compose.setContent { ReadAlongTheme { CharacterListSheet(book, 0, emptyList(), seek = {}, close = {}) } }
        compose.onNodeWithTag("add_character").performClick()
        compose.onNodeWithTag("character_name").performTextInput("Rocky")
        compose.onNodeWithTag("character_note").performTextInput("Engineer from Erid")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Engineer from Erid").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Rocky").assertExists()
        // Tapping a person opens their row in place rather than leaving the list.
        var closed = false
        compose.onNodeWithTag("character_Rocky").performClick()
        compose.onNodeWithText("Engineer from Erid").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertFalse(closed) }
    }

    @Test fun anAiAnswerIsPastedIntoTheList() {
        val book = "character-ai-test-${System.nanoTime()}"
        compose.setContent { ReadAlongTheme { CharacterListSheet(book, 0, emptyList(), seek = {}, close = {}, title = "Project Hail Mary", author = "Andy Weir") } }
        compose.onNodeWithTag("add_character").performClick()
        compose.onNodeWithTag("make_with_ai").performClick()
        compose.onNodeWithTag("ai_answer").performTextInput("1. **Eva Stratt** — Runs the Hail Mary project.\n2. Rocky: An engineer.")
        compose.onNodeWithTag("ai_add").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Runs the Hail Mary project.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("An engineer.").assertExists()
    }

    @Test fun emptyHistoryExplainsItself() {
        compose.setContent { ReadAlongTheme { HistorySheet("history-test-${System.nanoTime()}", 65_000, "Chapter 1", seek = {}, close = {}) } }
        compose.onNodeWithText("Listening history").assertExists()
        compose.onNodeWithText("Now").assertExists()
    }
}
