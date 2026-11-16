package com.example.readalong.domain

import com.example.readalong.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class LibraryGroupsTest {
    @Test
    fun sectionsRespectExplicitCompletionAndUnknownDuration() {
        val fresh = Book("fresh", "New book")
        val active = Book("active", "In progress", positionMs = 2_000)
        val finished = Book("finished", "Finished book", isFinished = true)
        val groups = libraryGroups(listOf(finished, fresh, active), "", LibrarySort.RECENT)
        assertEquals(listOf(LibrarySection.CURRENT, LibrarySection.NOT_STARTED, LibrarySection.FINISHED), groups.map { it.section })
        assertEquals(0, active.progressPercent)
        assertNull(active.remainingMs)
        assertEquals(100, finished.progressPercent)
    }

    @Test
    fun searchMatchesAuthorWithoutCaseOrOuterWhitespace() {
        val book = Book("sample", "The Night Orchard", "Mira Ellison")
        assertEquals(listOf(book), libraryGroups(listOf(book), "  MIRA  ", LibrarySort.TITLE).single().books)
        assertTrue(libraryGroups(listOf(book), "different", LibrarySort.TITLE).isEmpty())
    }

    @Test
    fun titleAndRecentSortingAreDeterministicWithinSections() {
        val a = Book("a", "Alpha", positionMs = 1, lastPlayedAt = 10)
        val z = Book("z", "Zebra", positionMs = 1, lastPlayedAt = 20)
        assertEquals(listOf(z, a), libraryGroups(listOf(a, z), "", LibrarySort.RECENT).single().books)
        assertEquals(listOf(a, z), libraryGroups(listOf(z, a), "", LibrarySort.TITLE).single().books)
    }

    @Test
    fun progressHandlesBadMetadataAndVeryLongBooks() {
        assertEquals(0, Book("a", "Unknown", durationMs = 0, positionMs = 100).progressPercent)
        assertEquals(99, Book("b", "At end", durationMs = 100, positionMs = 100).progressPercent)
        assertEquals(50, Book("c", "Long", durationMs = Long.MAX_VALUE, positionMs = Long.MAX_VALUE / 2).progressPercent)
        assertEquals(0, Book("d", "Before start", durationMs = 100, positionMs = -1).progressPercent)
    }

    @Test
    fun newHoldsTheLatestUnstartedAdditionsOnly() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        val recent = (1..12).map { Book("r$it", "Recent $it", addedAt = now - it * day) }
        val old = Book("old", "Old", addedAt = now - 60 * day)
        val started = Book("started", "Started", positionMs = 5, addedAt = now)
        val groups = libraryGroups(recent + old + started, "", LibrarySort.TITLE, now).associate { it.section to it.books.map { b -> b.id } }
        assertEquals(listOf("started"), groups[LibrarySection.CURRENT])
        assertEquals(NEW_BOOKS, groups[LibrarySection.NEW]!!.size)
        assertFalse("r11" in groups[LibrarySection.NEW]!!)
        assertEquals(setOf("r11", "r12", "old"), groups[LibrarySection.NOT_STARTED]!!.toSet())
    }
}
