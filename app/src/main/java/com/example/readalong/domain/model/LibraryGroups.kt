package com.example.readalong.domain.model

import java.util.Locale

enum class LibrarySort { RECENT, TITLE, AUTHOR }
data class LibraryGroup(val section: LibrarySection, val books: List<Book>)

/** The New section holds at most this many books, each added within [NEW_FOR_MS]. */
const val NEW_BOOKS = 10
const val NEW_FOR_MS = 30L * 24 * 60 * 60 * 1000

fun libraryGroups(books: List<Book>, query: String, sort: LibrarySort, now: Long = System.currentTimeMillis()): List<LibraryGroup> {
    val search = query.trim()
    val matching = books.filter {
        it.title.contains(search, ignoreCase = true) || it.author.orEmpty().contains(search, ignoreCase = true)
    }
    // Newest additions not yet started; worked out over the whole library so a search doesn't change what's new.
    val newIds = books.filter { it.section == LibrarySection.NOT_STARTED && !it.isDemo && now - it.addedAt in 0..NEW_FOR_MS }
        .sortedByDescending { it.addedAt }.take(NEW_BOOKS).map { it.id }.toSet()
    val grouped = matching.sortedWith(libraryOrder(sort)).groupBy { if (it.id in newIds) LibrarySection.NEW else it.section }
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
