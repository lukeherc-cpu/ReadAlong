package com.example.readalong.data.local

import androidx.room.*

/** User-approved wording, applied only while the original recognition still matches. */
@Entity(tableName = "transcript_corrections", primaryKeys = ["bookId", "segmentId"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class TranscriptCorrectionEntity(val bookId: String, val segmentId: String, val originalText: String,
    val replacement: String, val updatedAt: Long)
