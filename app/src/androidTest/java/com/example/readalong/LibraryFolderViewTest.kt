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
import org.junit.Rule
import org.junit.Test

/** The Sort and view menu switches the library to folders, and folders open and close. */
class LibraryFolderViewTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<ReadAlongApplication>()

    @Test fun foldersViewOpensFoldersAndGoesBackUp() {
        val original = runBlocking { app.appPreferences.preferences.first() }
        try {
            val books = listOf(Book("wing", "Fourth Wing", "Rebecca Yarros"), Book("iron", "Iron Flame", "Rebecca Yarros", positionMs = 10),
                Book("circe", "Circe", "Madeline Miller"))
            val places = mapOf("wing" to listOf("Fantasy", "Empyrean"), "iron" to listOf("Fantasy", "Empyrean"), "circe" to listOf("Myths"))
            compose.setContent { ReadAlongTheme { LibraryScreen(LibraryUiState.Ready(books), onRetry = {}, onAbout = {}, onBook = {}, bookPlaces = places) } }
            compose.onNodeWithTag("library_sort").performClick()
            compose.onNodeWithText("Author").assertExists()
            compose.onNodeWithTag("library_view_folders").performClick()
            compose.onNodeWithText("Folders").assertExists()
            // "Fantasy" holds only "Empyrean", so the two are one row.
            compose.onNodeWithText("Fantasy / Empyrean").assertExists()
            compose.onNodeWithText("2 books · 1 started · 1 not started").assertExists()
            compose.onNodeWithText("Circe").assertDoesNotExist()
            compose.onNodeWithText("Myths").performClick()
            compose.onNodeWithText("Circe").assertExists()
            compose.onNodeWithContentDescription("Up one folder").performClick()
            compose.onNodeWithText("Fantasy / Empyrean").assertExists()
        } finally { runBlocking { app.appPreferences.update { original } } }
    }
}
