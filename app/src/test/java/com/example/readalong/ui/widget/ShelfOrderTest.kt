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
}
