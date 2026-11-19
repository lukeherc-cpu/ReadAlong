package com.example.readalong.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfOrderTest {
    @Test fun playingABookDoesNotMoveIt() {
        assertEquals(listOf("a", "b", "c"), WidgetBooks.shelfOrder(listOf("a", "b", "c"), listOf("c", "a", "b")))
    }
    @Test fun aNewBookTakesTheNextPlaceAndAFinishedOneLeaves() {
        assertEquals(listOf("a", "c", "d"), WidgetBooks.shelfOrder(listOf("a", "b", "c"), listOf("d", "a", "c")))
    }
    @Test fun anEmptyShelfStartsMostRecentFirst() {
        assertEquals(listOf("x", "y"), WidgetBooks.shelfOrder(emptyList(), listOf("x", "y")))
    }

    // A book never played has no lastPlayedAt; mixing it with played books once crashed the setup screen.
    @Test fun setupListSortsPlayedAndNeverPlayedBooks() {
        val books = listOf(
            com.example.readalong.domain.model.Book("a", "Alpha"),
            com.example.readalong.domain.model.Book("b", "Beta", lastPlayedAt = 2_000L, positionMs = 1),
            com.example.readalong.domain.model.Book("c", "Gamma", lastPlayedAt = 1_000L, positionMs = 1),
            com.example.readalong.domain.model.Book("d", "Delta", isFinished = true, lastPlayedAt = 3_000L),
        )
        assertEquals(listOf("b", "c", "a", "d"), WidgetBooks.setupOrder(books).map { it.id })
    }
}
