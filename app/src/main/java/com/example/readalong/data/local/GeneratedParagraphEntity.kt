package com.example.readalong.data.local

import androidx.room.*

@Entity(tableName = "generated_paragraphs", primaryKeys = ["bookId", "segmentId"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class GeneratedParagraphEntity(val bookId: String, val segmentId: String, val text: String, val href: String)

/** Staged editions become visible only when readable_versions selects their edition ID. */
@Entity(tableName = "epub_paragraphs", primaryKeys = ["bookId", "editionId", "segmentId"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class EpubParagraphEntity(val bookId: String, val editionId: String, val segmentId: String, val text: String, val href: String,
    @ColumnInfo(defaultValue = "''") val chapterId: String = "",
    @ColumnInfo(defaultValue = "0") val startMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val endMs: Long = 0,
    @ColumnInfo(defaultValue = "'[]'") val anchorsJson: String = "[]")
