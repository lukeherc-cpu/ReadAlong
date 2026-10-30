package com.example.readalong

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.ui.importing.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportFlowTest {
    @get:Rule val compose = createComposeRule()
    @Test fun laterSkipsSourceChoiceAndCreateShowsItWithoutStartingRecognition() {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val id = "import-flow-${System.nanoTime()}"
        val model = ImportViewModel(app.audioImporter, app.importedEpub, app.processing)
        try {
            runBlocking {
                app.database.bookDao().upsertBooks(listOf(com.example.readalong.data.book.DemoBooks.books.first().copy(id = id, isDemo = false)))
                app.processing.imported(id); model.offerPriority(listOf(id))
            }
            compose.setContent { com.example.readalong.ui.theme.ReadAlongTheme { ImportPriorityPrompt(model) } }
            // One step: the new book's "Make a ReadAlong" choice appears straight away.
            compose.onNodeWithText("Make a ReadAlong").assertIsDisplayed()
            compose.onNodeWithTag("reading_source_epub").assertIsDisplayed()
            compose.onNodeWithText("Later").performClick()
            compose.onNodeWithTag("reading_source_epub").assertDoesNotExist()
            assertEquals("WAITING_SELECTION", runBlocking { app.database.processingDao().find(id)!!.state })
            runBlocking { model.offerPriority(listOf(id)) }
            compose.onNodeWithTag("reading_source_epub").assertIsDisplayed()
            val left = compose.onNodeWithTag("reading_source_transcript").fetchSemanticsNode().boundsInRoot
            val right = compose.onNodeWithTag("reading_source_epub").fetchSemanticsNode().boundsInRoot
            assertEquals(left.height, right.height, .5f)
            assertEquals(left.width, right.width, .5f)
            assertEquals("WAITING_SELECTION", runBlocking { app.database.processingDao().find(id)!!.state })
            compose.onNodeWithText("Later").performClick()
            compose.onNodeWithTag("reading_source_epub").assertDoesNotExist()
        } finally { runBlocking { app.database.bookDao().removeBook(id) } }
    }
}
