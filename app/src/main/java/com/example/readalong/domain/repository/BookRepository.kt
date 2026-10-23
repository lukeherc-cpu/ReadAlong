package com.example.readalong.domain.repository

import com.example.readalong.domain.model.Book
import kotlinx.coroutines.flow.Flow

interface BookRepository {
    fun observeBooks(): Flow<List<Book>>
    fun observeBook(bookId: String): Flow<Book?>
    suspend fun setFinished(bookId: String, finished: Boolean)
    suspend fun updateListeningPosition(bookId: String, positionMs: Long)
}
