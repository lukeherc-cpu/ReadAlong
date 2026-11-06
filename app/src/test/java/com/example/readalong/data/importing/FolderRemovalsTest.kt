package com.example.readalong.data.importing

import com.example.readalong.data.local.FolderBook
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderRemovalsTest {
    private val rows = listOf(FolderBook("content://a", "a1"), FolderBook("content://a", "a2"), FolderBook("content://b", "b1"))

    @Test fun removingAFolderHidesOnlyItsBooksUntilItIsAddedAgain() = runBlocking {
        val hidden = FolderRemovals.hiddenBooks { flowOf(rows) }
        assertEquals(emptySet<String>(), hidden.first())
        FolderRemovals.start("content://a")
        try {
            assertEquals(setOf("a1", "a2"), hidden.first())
        } finally { FolderRemovals.finish("content://a") }
        assertEquals(emptySet<String>(), hidden.first())
    }
}
