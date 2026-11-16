package com.example.readalong.domain

import com.example.readalong.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class BookNotesTest {
    @Test
    fun historyKeepsTheLastTenAndMergesQuickSkips() {
        var history = emptyList<HistoryEntry>()
        history = ListeningHistory.add(history, HistoryEntry(100, JumpKind.SKIP_FORWARD, "Chapter 1", 1_000))
        history = ListeningHistory.add(history, HistoryEntry(130, JumpKind.SKIP_FORWARD, "Chapter 1", 3_000))
        assertEquals(1, history.size)
        assertEquals(100, history.single().positionMs)
        history = ListeningHistory.add(history, HistoryEntry(500, JumpKind.CHAPTER, "Chapter 1", 20_000))
        assertEquals(listOf(JumpKind.CHAPTER, JumpKind.SKIP_FORWARD), history.map { it.kind })
        repeat(15) { history = ListeningHistory.add(history, HistoryEntry(it.toLong(), JumpKind.SEEK, "x", 100_000L + it * 100_000)) }
        assertEquals(ListeningHistory.LIMIT, history.size)
        assertEquals(14L, history.first().positionMs)
        assertEquals(history, ListeningHistory.decode(ListeningHistory.encode(history)))
    }

    @Test
    fun charactersSurviveTabsAndNewLinesInNotes() {
        val list = listOf(CharacterEntry("1", "Rocky", "an\talien\nfriend", 5), CharacterEntry("2", "Eva Stratt"))
        val decoded = CharacterList.decode(CharacterList.encode(list))
        assertEquals("an alien friend", decoded.first().note)
        assertNull(decoded.last().firstMs)
        assertEquals(listOf("Eva Stratt", "Rocky"), CharacterList.sorted(decoded).map { it.name })
    }

    @Test
    fun suggestionsFindRecurringNamesButNotSentenceStartsOrOrdinaryWords() {
        val text = listOf(
            0L to "I wake up. Rocky is tapping on the wall. I wave at Rocky and Rocky waves back.",
            1_000L to "Eva Stratt was waiting. \"Doctor Grace,\" said Eva Stratt, \"you're late.\" I hated Stratt.",
            2_000L to "Later Eva Stratt called. Then Rocky laughed. We had hope. Hope is a lie, said Eva Stratt on Monday.",
        )
        val names = suggestNames(text)
        assertEquals(listOf("Eva Stratt", "Rocky"), names.map { it.name })
        assertEquals(1_000L, names.first().firstMs)
        assertEquals(listOf("Eva Stratt"), suggestNames(text, known = listOf("Rocky")).map { it.name })
    }
}
