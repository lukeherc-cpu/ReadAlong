package com.example.readalong.domain.model

import java.util.Locale

enum class LibrarySort { RECENT, TITLE }
data class LibraryGroup(val section: LibrarySection, val books: List<Book>)

fun libraryGroups(books: List<Book>, query: String, sort: LibrarySort): List<LibraryGroup> {
    val search = query.trim()
    val matching = books.filter {
        it.title.contains(search, ignoreCase = true) || it.author.orEmpty().contains(search, ignoreCase = true)
    }
    val titleOrder = compareBy<Book> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
    val order = when (sort) {
        LibrarySort.TITLE -> titleOrder
        LibrarySort.RECENT -> compareByDescending<Book> { it.lastPlayedAt ?: Long.MIN_VALUE }
            .thenByDescending { it.addedAt }.then(titleOrder)
    }
    val grouped = matching.sortedWith(order).groupBy { it.section }
    return LibrarySection.entries.mapNotNull { section ->
        grouped[section]?.let { LibraryGroup(section, it) }
    }
}
