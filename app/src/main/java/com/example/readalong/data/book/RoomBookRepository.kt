package com.example.readalong.data.book

import androidx.room.withTransaction
import com.example.readalong.data.local.BookProgressEntity
import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.domain.model.Book
import com.example.readalong.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class RoomBookRepository(private val database: ReadAlongDatabase, private val seedSamples: Boolean = false) : BookRepository {
    private val dao = database.bookDao()

    override fun observeBooks(): Flow<List<Book>> = flow {
        seedDemoLibraryOnce()
        emitAll(dao.observeBooks().map { rows -> rows.map { it.toBook() } })
    }

    override fun observeBook(bookId: String): Flow<Book?> = flow {
        seedDemoLibraryOnce()
        emitAll(dao.observeBook(bookId).map { it?.toBook() })
    }

    override suspend fun setFinished(bookId: String, finished: Boolean) {
        database.withTransaction {
            checkNotNull(dao.findBook(bookId)) { "Book is no longer in the library" }
            dao.insertProgress(listOf(BookProgressEntity(bookId)))
            // A user marking a book finished must not change the actual playback timestamp.
            dao.setFinished(bookId, finished)
        }
    }

    override suspend fun updateListeningPosition(bookId: String, positionMs: Long) {
        database.withTransaction {
            val book = checkNotNull(dao.findBook(bookId)) { "Book is no longer in the library" }
            val bounded = book.durationMs?.takeIf { it > 0 }?.let { positionMs.coerceIn(0, it) }
                ?: positionMs.coerceAtLeast(0)
            dao.insertProgress(listOf(BookProgressEntity(bookId)))
            dao.updateListeningPosition(bookId, bounded, System.currentTimeMillis())
        }
    }

    private suspend fun seedDemoLibraryOnce() {
        database.withTransaction {
            if (!seedSamples) {
                if (dao.metadata("sample_cleanup_v2") == null) {
                    dao.removeSamples()
                    dao.insertMetadata(LibraryMetadataEntity("sample_cleanup_v2", "complete"))
                }
            } else if (dao.metadata("demo_seed_v1") == null) {
                dao.insertBooks(DemoBooks.books)
                dao.insertProgress(DemoBooks.progress)
                dao.insertMetadata(LibraryMetadataEntity("demo_seed_v1", "complete"))
            }
        }
    }
}
