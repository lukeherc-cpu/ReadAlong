package com.example.readalong.domain.model

import java.util.Locale

/** A group of books the listener made, such as "Road trip". A book can be in any number of collections. */
data class BookCollection(val id: String, val name: String, val bookIds: List<String>, val createdAt: Long) {
    /** Stored as the name, the time it was made, then one book id per line. */
    fun encode(): String = (listOf(cleanName(name), createdAt.toString()) + bookIds.distinct()).joinToString("\n")
    fun with(bookId: String, inside: Boolean) = copy(bookIds = if (inside) (bookIds - bookId) + bookId else bookIds - bookId)

    companion object {
        fun decode(id: String, value: String): BookCollection? {
            val lines = value.split('\n')
            val name = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
            return BookCollection(id, name, lines.drop(2).filter { it.isNotBlank() }.distinct(), lines.getOrNull(1)?.toLongOrNull() ?: 0)
        }
        /** One line, no longer than 80 characters. */
        fun cleanName(name: String) = name.replace(Regex("\\s+"), " ").trim().take(80)
    }
}

/** One row of the Collections view: the collection and its books that are still in the library, in [LibrarySort] order. */
data class CollectionRow(val collection: BookCollection?, val name: String, val books: List<Book>)

/**
 * The Collections view: each collection by name, then "Not in a collection" for every book in none, so
 * nothing goes missing. Books that were deleted from the library are left out.
 */
fun collectionRows(collections: List<BookCollection>, books: List<Book>, sort: LibrarySort): List<CollectionRow> {
    val ordered = books.sortedWith(libraryOrder(sort))
    val rows = collections.sortedWith(compareBy<BookCollection> { it.name.lowercase(Locale.ROOT) }.thenBy { it.createdAt }).map { collection ->
        val ids = collection.bookIds.toSet()
        CollectionRow(collection, collection.name, ordered.filter { it.id in ids })
    }
    val filed = collections.flatMap { it.bookIds }.toSet()
    val loose = ordered.filter { it.id !in filed }
    return if (loose.isEmpty()) rows else rows + CollectionRow(null, NOT_IN_A_COLLECTION, loose)
}

const val NOT_IN_A_COLLECTION = "Not in a collection"

/** "3 books · 1 started · 2 new", for a collection's row. */
fun collectionSummary(books: List<Book>): String {
    val started = books.count { it.section == LibrarySection.CURRENT }
    val finished = books.count { it.section == LibrarySection.FINISHED }
    val fresh = books.size - started - finished
    return buildList {
        add(if (books.size == 1) "1 book" else "${books.size} books")
        if (started > 0) add("$started started")
        if (fresh > 0 && fresh != books.size) add("$fresh new")
        if (finished > 0) add(if (finished == books.size) "all finished" else "$finished finished")
    }.joinToString(" · ")
}
