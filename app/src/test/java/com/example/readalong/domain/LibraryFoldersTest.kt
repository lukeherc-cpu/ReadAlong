package com.example.readalong.domain

import com.example.readalong.data.book.BookFolderPlaces
import com.example.readalong.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class LibraryFoldersTest {
    private fun file(book: String, vararg dirs: String, root: String? = "tree:a", rootName: String? = "Audiobooks") =
        BookFileLocation(book, root, rootName, dirs.toList())

    @Test
    fun aFolderHoldingOneBookIsThatBook() {
        val places = bookPlaces(listOf(
            file("wing", "Fantasy", "Fourth Wing"), file("wing", "Fantasy", "Fourth Wing"),
            file("iron", "Fantasy", "Iron Flame", "Disc 1"), file("iron", "Fantasy", "Iron Flame", "Disc 2"),
        ))
        assertEquals(listOf("Fantasy"), places["wing"])
        assertEquals(listOf("Fantasy"), places["iron"])
    }

    @Test
    fun aFolderSharedBySeveralBooksListsThem() {
        val places = bookPlaces(listOf(file("a", "Singles"), file("b", "Singles"), file("c")))
        assertEquals(listOf("Singles"), places["a"])
        assertEquals(listOf("Singles"), places["b"])
        assertEquals(emptyList<String>(), places["c"])
    }

    @Test
    fun severalLibraryFoldersEachBecomeATopFolder() {
        val places = bookPlaces(listOf(file("a", "Book A"), file("b", "Book B", root = "tree:b", rootName = "SD card")))
        assertEquals(listOf("Audiobooks"), places["a"])
        assertEquals(listOf("SD card"), places["b"])
    }

    @Test
    fun levelsListFoldersThenBooksAndMergeSingleFolderChains() {
        val books = listOf(Book("a", "Alpha"), Book("b", "Beta", positionMs = 5), Book("c", "Gamma", isFinished = true), Book("demo", "Demo"))
        val places = mapOf("a" to listOf("Audiobooks", "Fantasy"), "b" to listOf("Audiobooks", "Fantasy"), "c" to listOf("Audiobooks", "Classics", "Russian"))
        val top = folderLevel(books, places, emptyList(), LibrarySort.TITLE)
        assertEquals(listOf("Demo"), top.books.map { it.title })
        assertEquals(listOf("Audiobooks"), top.folders.map { it.name })
        val inside = folderLevel(books, places, listOf("Audiobooks"), LibrarySort.TITLE)
        assertEquals(listOf("Classics / Russian", "Fantasy"), inside.folders.map { it.name })
        assertEquals(listOf("Audiobooks", "Classics", "Russian"), inside.folders.first().path)
        assertEquals(listOf("a", "b"), folderLevel(books, places, listOf("Audiobooks", "Fantasy"), LibrarySort.TITLE).books.map { it.id })
    }

    @Test
    fun authorSortPutsBooksWithoutAnAuthorLast() {
        val books = listOf(Book("x", "Zed"), Book("m", "Middle", "miller"), Book("a", "Alpha", "Weir"), Book("b", "Beta", "Adams"))
        assertEquals(listOf("b", "m", "a", "x"), libraryGroups(books, "", LibrarySort.AUTHOR).single().books.map { it.id })
    }

    @Test
    fun relativeDirsReadPathsFromDocumentIds() {
        assertEquals(listOf("Fantasy", "Fourth Wing"), BookFolderPlaces.relativeDirs("primary:Audiobooks", "primary:Audiobooks/Fantasy/Fourth Wing/01.mp3"))
        assertEquals(listOf("Audiobooks"), BookFolderPlaces.relativeDirs("primary:", "primary:Audiobooks/book.m4b"))
        assertEquals(emptyList<String>(), BookFolderPlaces.relativeDirs("primary:Audiobooks", "primary:AudiobooksOld/x.mp3"))
        assertEquals(emptyList<String>(), BookFolderPlaces.relativeDirs("msf:12", "msf:345"))
    }
}
