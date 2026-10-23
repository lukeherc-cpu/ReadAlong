package com.example.readalong.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Transaction
    @Query("SELECT * FROM books ORDER BY addedAt DESC, title COLLATE NOCASE, id")
    fun observeBooks(): Flow<List<BookWithProgress>>

    @Transaction
    @Query("SELECT * FROM books WHERE id = :bookId")
    fun observeBook(bookId: String): Flow<BookWithProgress?>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun findBook(bookId: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBooks(books: List<BookEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProgress(progress: List<BookProgressEntity>)

    @Query("UPDATE book_progress SET isFinished = :finished WHERE bookId = :bookId")
    suspend fun setFinished(bookId: String, finished: Boolean)

    @Query("UPDATE book_progress SET audioPositionMs = :positionMs, lastPlayedAt = :playedAt WHERE bookId = :bookId")
    suspend fun updateListeningPosition(bookId: String, positionMs: Long, playedAt: Long)

    @Upsert
    suspend fun upsertBooks(books: List<BookEntity>)

    @Upsert
    suspend fun putMetadata(metadata: LibraryMetadataEntity)

    @Query("SELECT * FROM book_progress WHERE bookId = :bookId")
    suspend fun progress(bookId: String): BookProgressEntity?

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun removeBook(bookId: String)

    @Query("DELETE FROM books WHERE isDemo = 1")
    suspend fun removeSamples()

    @Query("UPDATE book_progress SET audioPositionMs = 0, isFinished = 0, lastPlayedAt = NULL WHERE bookId = :bookId")
    suspend fun markNotStarted(bookId: String)

    @Query("SELECT value FROM library_metadata WHERE `key` = :key")
    suspend fun metadata(key: String): String?

    @Query("SELECT value FROM library_metadata WHERE `key` = :key")
    fun observeMetadata(key: String): kotlinx.coroutines.flow.Flow<String?>

    @Query("DELETE FROM library_metadata WHERE `key` = :key")
    suspend fun removeMetadata(key: String)

    @Query("DELETE FROM library_metadata WHERE substr(`key`, -length(:bookId) - 1) = ':' || :bookId")
    suspend fun removeBookMetadata(bookId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMetadata(metadata: LibraryMetadataEntity)
}
