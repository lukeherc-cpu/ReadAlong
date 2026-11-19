package com.example.readalong

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.domain.model.Book
import com.example.readalong.ui.library.LibraryScreen
import com.example.readalong.ui.library.LibraryUiState
import com.example.readalong.ui.theme.ReadAlongTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** The Collections view: making a collection, picking its books, opening it, and books in no collection. */
class LibraryCollectionsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
    private val original = runBlocking { app.appPreferences.preferences.first() }
    private val books = listOf(Book("wing", "Fourth Wing", "Rebecca Yarros"), Book("iron", "Iron Flame", "Rebecca Yarros", positionMs = 10),
        Book("circe", "Circe", "Madeline Miller"))

    @After fun restore() = runBlocking {
        app.collections.all.first().forEach { app.collections.delete(it) }
        app.appPreferences.update { original }
    }

    @Test fun makeACollectionAndPickItsBooks() {
        runBlocking { app.collections.all.first().forEach { app.collections.delete(it) } }
        compose.setContent { ReadAlongTheme { LibraryScreen(LibraryUiState.Ready(books), onRetry = {}, onAbout = {}, onBook = {}) } }
        compose.onNodeWithTag("library_sort").performClick()
        compose.onNodeWithTag("library_view_collections").performClick()
        compose.onNodeWithText("Collections").assertExists()
        compose.onNodeWithText("Not in a collection").assertExists()
        compose.onNodeWithTag("new_collection").performClick()
        compose.onNodeWithTag("collection_name").performTextInput("Empyrean")
        compose.onNodeWithTag("collection_name_save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("collection_picker").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pick_wing").performClick()
        compose.onNodeWithTag("pick_iron").performClick()
        compose.onNodeWithText("Add 2 books").performClick()
        // Saving opens the new collection.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("collection_header").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Fourth Wing").assertExists()
        compose.onNodeWithText("Circe").assertDoesNotExist()
        compose.onNodeWithContentDescription("All collections").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("2 books · 1 started").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Not in a collection").performClick()
        compose.onNodeWithText("Circe").assertExists()
    }

    @Test fun deletingACollectionKeepsItsBooks() {
        runBlocking { app.collections.all.first().forEach { app.collections.delete(it) }; app.collections.create("Myths", listOf("circe")) }
        compose.setContent { ReadAlongTheme { LibraryScreen(LibraryUiState.Ready(books), onRetry = {}, onAbout = {}, onBook = {}) } }
        compose.onNodeWithTag("library_sort").performClick()
        compose.onNodeWithTag("library_view_collections").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Myths").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Myths").performClick()
        compose.onNodeWithTag("collection_menu").performClick()
        compose.onNodeWithTag("collection_delete").performClick()
        compose.onNodeWithTag("collection_delete_confirm").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Myths").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Not in a collection").performClick()
        compose.onNodeWithText("Circe").assertExists()
    }
}
