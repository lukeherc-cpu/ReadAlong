package com.example.readalong.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.example.readalong.domain.model.Book

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val coverUri: String?,
    val description: String?,
    val durationMs: Long?,
    val addedAt: Long,
    val isDemo: Boolean,
    val coverStyle: Int,
    @ColumnInfo(defaultValue = "'NONE'") val mediaState: String = "NONE",
)

@Entity(
    tableName = "book_progress",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class BookProgressEntity(
    @PrimaryKey val bookId: String,
    val audioPositionMs: Long = 0,
    val isFinished: Boolean = false,
    val lastPlayedAt: Long? = null,
)

data class BookWithProgress(
    @Embedded val book: BookEntity,
    @Relation(parentColumn = "id", entityColumn = "bookId") val progress: BookProgressEntity?,
) {
    fun toBook() = Book(
        id = book.id,
        title = book.title,
        author = book.author,
        coverUri = book.coverUri,
        description = book.description,
        durationMs = book.durationMs,
        positionMs = progress?.audioPositionMs ?: 0,
        isFinished = progress?.isFinished ?: false,
        lastPlayedAt = progress?.lastPlayedAt,
        addedAt = book.addedAt,
        isDemo = book.isDemo,
        coverStyle = book.coverStyle,
        mediaState = book.mediaState,
    )
}

@Entity(tableName = "library_metadata")
data class LibraryMetadataEntity(@PrimaryKey val key: String, val value: String)
