package com.example.readalong.domain

import com.example.readalong.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class CollectionsTest {
    private val wing = Book("wing", "Fourth Wing", "Rebecca Yarros")
    private val iron = Book("iron", "Iron Flame", "Rebecca Yarros", positionMs = 10)
    private val circe = Book("circe", "Circe", "Madeline Miller", isFinished = true, positionMs = 5)

    @Test fun storedCollectionsReadBack() {
        val collection = BookCollection("a", "  Road\ntrip ", listOf("wing", "iron", "wing"), 42)
        val back = BookCollection.decode("a", collection.encode())!!
        assertEquals("Road trip", back.name)
        assertEquals(listOf("wing", "iron"), back.bookIds)
        assertEquals(42, back.createdAt)
        assertNull(BookCollection.decode("b", ""))
    }

    @Test fun booksInNoCollectionAreListedLast() {
        val rows = collectionRows(listOf(BookCollection("f", "Fantasy", listOf("iron", "wing", "gone"), 1)), listOf(wing, iron, circe), LibrarySort.TITLE)
        assertEquals(listOf("Fantasy", NOT_IN_A_COLLECTION), rows.map { it.name })
        assertEquals(listOf("Fourth Wing", "Iron Flame"), rows[0].books.map { it.title })
        assertEquals(listOf("circe"), rows[1].books.map { it.id })
        assertNull(rows[1].collection)
    }

    @Test fun everyBookFiledLeavesNoLooseRow() {
        val rows = collectionRows(listOf(BookCollection("all", "All", listOf("wing", "iron", "circe"), 1)), listOf(wing, iron, circe), LibrarySort.RECENT)
        assertEquals(1, rows.size)
    }

    @Test fun aBookCanBeAddedAndTakenOut() {
        val empty = BookCollection("e", "Bedtime", emptyList(), 1)
        assertEquals(listOf("wing"), empty.with("wing", true).bookIds)
        assertEquals(listOf("wing"), empty.with("wing", true).with("wing", true).bookIds)
        assertEquals(emptyList<String>(), empty.with("wing", true).with("wing", false).bookIds)
    }

    @Test fun summaryCountsStages() {
        assertEquals("3 books · 1 started", collectionSummary(listOf(wing, iron, circe)))
        assertEquals("1 book · all finished", collectionSummary(listOf(circe)))
    }
}
