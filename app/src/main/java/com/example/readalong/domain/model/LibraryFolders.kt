package com.example.readalong.domain.model

import java.util.Locale

/**
 * Where one audio file of a book sits: the library folder it was found in ([root], shown as [rootName])
 * and the folders between that library folder and the file, outermost first.
 */
data class BookFileLocation(val bookId: String, val root: String?, val rootName: String?, val dirs: List<String>)

/**
 * Each book's place in the library's folder view, as folder names from the top.
 *
 * A folder that holds a single book is that book, so the book is listed in the folder above it
 * ("Fantasy/Fourth Wing/01.mp3" puts Fourth Wing in "Fantasy"). A folder that several books share,
 * such as a folder of .m4b files, lists those books itself. With more than one library folder, each
 * library folder is a top-level folder. Books with no files (the demo book) are not in the map and
 * belong at the top.
 */
fun bookPlaces(files: List<BookFileLocation>): Map<String, List<String>> {
    val multipleRoots = files.mapNotNull { it.root }.distinct().size > 1
    val shared = files.groupBy { it.bookId }.mapValues { (_, bookFiles) ->
        val first = bookFiles.first()
        val dirs = bookFiles.filter { it.root == first.root }.map { it.dirs }
            .reduce { a, b -> a.zip(b).takeWhile { (x, y) -> x == y }.map { it.first } }
        first to dirs
    }
    val booksPerFolder = shared.values.groupingBy { (file, dirs) -> file.root to dirs }.eachCount()
    return shared.mapValues { (_, value) ->
        val (file, dirs) = value
        val ownFolder = dirs.isNotEmpty() && booksPerFolder[file.root to dirs] == 1
        val inside = if (ownFolder) dirs.dropLast(1) else dirs
        if (multipleRoots && file.root != null) listOf(file.rootName?.takeIf { it.isNotBlank() } ?: "Library folder") + inside else inside
    }
}

/** A folder in the folder view: its [name] as shown (one or more folder names joined), where it leads, and every book under it. */
data class FolderNode(val name: String, val path: List<String>, val books: List<Book>)

/** What one level of the folder view shows: the folders inside it, then the books directly in it. */
data class FolderLevel(val folders: List<FolderNode>, val books: List<Book>)

/**
 * The folders and books at [path]. A folder with nothing in it but one other folder is merged with
 * it ("Audiobooks / Fantasy"), so nobody taps through empty levels. Folders are listed by name and
 * books in [sort] order.
 */
fun folderLevel(books: List<Book>, places: Map<String, List<String>>, path: List<String>, sort: LibrarySort): FolderLevel {
    fun placeOf(book: Book) = places[book.id].orEmpty()
    fun under(book: Book, at: List<String>) = placeOf(book).let { it.size >= at.size && it.subList(0, at.size) == at }
    val sorted = books.sortedWith(libraryOrder(sort))
    val (here, deeper) = sorted.filter { under(it, path) }.partition { placeOf(it).size == path.size }
    val folders = deeper.groupBy { placeOf(it)[path.size] }.map { (name, inside) ->
        var node = FolderNode(name, path + name, inside)
        while (true) {
            val next = inside.map { placeOf(it) }
            if (next.any { it.size == node.path.size }) break
            val children = next.map { it[node.path.size] }.distinct()
            if (children.size != 1) break
            node = FolderNode("${node.name} / ${children.single()}", node.path + children.single(), inside)
        }
        node
    }.sortedBy { it.name.lowercase(Locale.ROOT) }
    return FolderLevel(folders, here)
}
