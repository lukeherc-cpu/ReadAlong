package com.example.readalong.testutil

import com.example.readalong.domain.model.Book
import com.example.readalong.domain.repository.BookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

open class FakeBookRepository(initialBooks: List<Book> = emptyList()) : BookRepository {
    val books = MutableStateFlow(initialBooks)
    override fun observeBooks(): Flow<List<Book>> = books
    override fun observeBook(bookId: String) = books.map { rows -> rows.find { it.id == bookId } }
    override suspend fun setFinished(bookId: String, finished: Boolean) {
        books.value = books.value.map { if (it.id == bookId) it.copy(isFinished = finished) else it }
    }
    override suspend fun updateListeningPosition(bookId: String, positionMs: Long) {
        books.value = books.value.map { if (it.id == bookId) it.copy(positionMs = positionMs) else it }
    }
}
