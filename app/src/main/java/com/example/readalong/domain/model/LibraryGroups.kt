package com.example.readalong.domain.model

import java.util.Locale

enum class LibrarySort { RECENT, TITLE, AUTHOR }
data class LibraryGroup(val section: LibrarySection, val books: List<Book>)

fun libraryGroups(books: List<Book>, query: String, sort: LibrarySort): List<LibraryGroup> {
    val search = query.trim()
    val matching = books.filter {
        it.title.contains(search, ignoreCase = true) || it.author.orEmpty().contains(search, ignoreCase = true)
    }
    val grouped = matching.sortedWith(libraryOrder(sort)).groupBy { it.section }
    return LibrarySection.entries.mapNotNull { section ->
        grouped[section]?.let { LibraryGroup(section, it) }
    }
}

/** The order books are listed in, within a section or a folder. */
fun libraryOrder(sort: LibrarySort): Comparator<Book> {
    val titleOrder = compareBy<Book> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
    return when (sort) {
        LibrarySort.TITLE -> titleOrder
        // Books with no author go last, then each author's books by title.
        LibrarySort.AUTHOR -> compareBy<Book> { it.author.isNullOrBlank() }
            .thenBy { it.author.orEmpty().trim().lowercase(Locale.ROOT) }.then(titleOrder)
        LibrarySort.RECENT -> compareByDescending<Book> { it.lastPlayedAt ?: Long.MIN_VALUE }
            .thenByDescending { it.addedAt }.then(titleOrder)
    }
}
