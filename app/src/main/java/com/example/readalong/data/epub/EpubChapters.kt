package com.example.readalong.data.epub

import com.example.readalong.data.local.ImportedEpubBlockEntity
import com.example.readalong.data.local.ImportedEpubEntity
import com.example.readalong.data.local.ReadAlongDatabase

/** One EPUB contents entry; [startMs] is the earliest matched audio time in its section, or -1 when unmatched. */
data class TimedEpubChapter(val ordinal: Int, val title: String, val startMs: Long)

/** Shared by the reader's Contents and the player's chapter list so both always show the same chapters and times. */
suspend fun ReadAlongDatabase.epubChapters(edition: ImportedEpubEntity): List<TimedEpubChapter> {
    val blocks = importedEpubDao().blocks(edition.bookId, edition.editionId)
    val nav = runCatching {
        val rows = org.json.JSONArray(bookDao().metadata("epub_contents:${edition.editionId}") ?: "[]")
        (0 until rows.length()).map { rows.getJSONObject(it).let { row -> row.getInt("ordinal") to row.getString("title") } }
    }.getOrDefault(emptyList())
    return epubChapters(nav, blocks)
}

fun epubChapters(nav: List<Pair<Int, String>>, blocks: List<ImportedEpubBlockEntity>): List<TimedEpubChapter> {
    val entries = if (nav.isNotEmpty()) nav else blocks.groupBy { it.spine }.values.map { section ->
        section.first().ordinal to (section.filter { it.kind.startsWith("H") }.take(3).joinToString(" · ") { it.text }.ifBlank { section.first().text.take(100) })
    }
    val ordered = entries.distinctBy { it.first }.sortedBy { it.first }
    return ordered.mapIndexed { index, (ordinal, title) ->
        val end = ordered.getOrNull(index + 1)?.first ?: Int.MAX_VALUE
        val start = blocks.asSequence().filter { it.ordinal in ordinal until end && it.startMs >= 0 }.minOfOrNull { it.startMs } ?: -1
        TimedEpubChapter(ordinal, title, start)
    }
}
