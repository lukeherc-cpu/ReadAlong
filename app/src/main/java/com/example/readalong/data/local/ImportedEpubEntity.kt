package com.example.readalong.data.local

import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "imported_epubs", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class ImportedEpubEntity(@PrimaryKey val bookId: String, val editionId: String, val uri: String, val title: String,
    val state: String, val message: String, val preference: String, val alignedRevision: Long,
    val matchedSegments: Int, val totalSegments: Int, val matchedTokens: Int, val totalTokens: Int, val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val manualCount: Int = 0)

@Entity(tableName = "imported_epub_blocks", primaryKeys = ["bookId", "editionId", "ordinal"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId", "editionId", "startMs"])])
data class ImportedEpubBlockEntity(val bookId: String, val editionId: String, val ordinal: Int, val spine: Int,
    val href: String, val text: String, val kind: String, val stylesJson: String, val centered: Boolean,
    val anchorsJson: String, val startMs: Long, val endMs: Long, val confidence: Float,
    @ColumnInfo(defaultValue = "NULL") val automaticAnchorsJson: String? = null,
    @ColumnInfo(defaultValue = "NULL") val automaticConfidence: Float? = null,
    @ColumnInfo(defaultValue = "-1") val manualStartMs: Long = -1,
    @ColumnInfo(defaultValue = "-1") val manualEndMs: Long = -1)

@Dao
interface ImportedEpubDao {
    @Query("SELECT * FROM imported_epubs WHERE bookId = :bookId") fun observe(bookId: String): Flow<ImportedEpubEntity?>
    @Query("SELECT * FROM imported_epubs WHERE bookId = :bookId") suspend fun find(bookId: String): ImportedEpubEntity?
    @Query("SELECT * FROM imported_epubs") suspend fun all(): List<ImportedEpubEntity>
    @Upsert suspend fun put(edition: ImportedEpubEntity)
    @Upsert suspend fun putBlocks(blocks: List<ImportedEpubBlockEntity>)
    @Query("SELECT * FROM imported_epub_blocks WHERE bookId = :bookId AND editionId = :editionId ORDER BY ordinal")
    fun paging(bookId: String, editionId: String): PagingSource<Int, ImportedEpubBlockEntity>
    @Query("SELECT * FROM imported_epub_blocks WHERE bookId = :bookId AND editionId = :editionId ORDER BY ordinal LIMIT :limit OFFSET :offset") suspend fun blockPage(bookId: String, editionId: String, limit: Int, offset: Int): List<ImportedEpubBlockEntity>
    @Query("SELECT * FROM imported_epub_blocks WHERE bookId = :bookId AND editionId = :editionId ORDER BY ordinal") suspend fun blocks(bookId: String, editionId: String): List<ImportedEpubBlockEntity>
    @Query("SELECT b.* FROM imported_epub_blocks b INNER JOIN imported_epubs e ON e.bookId = b.bookId AND e.editionId = b.editionId WHERE b.bookId = :bookId AND b.startMs <= :position AND b.endMs > :position ORDER BY b.ordinal")
    fun at(bookId: String, position: Long): Flow<List<ImportedEpubBlockEntity>>
    @Query("SELECT b.ordinal FROM imported_epub_blocks b INNER JOIN imported_epubs e ON e.bookId = b.bookId AND e.editionId = b.editionId WHERE b.bookId = :bookId AND b.startMs >= 0 AND b.startMs <= :position AND b.endMs > :position ORDER BY CASE WHEN b.manualStartMs >= 0 THEN 0 ELSE 1 END, b.ordinal LIMIT 1")
    suspend fun indexAt(bookId: String, position: Long): Int?
    @Query("SELECT * FROM imported_epub_blocks WHERE bookId = :bookId AND editionId = :editionId AND ordinal = :ordinal")
    suspend fun block(bookId: String, editionId: String, ordinal: Int): ImportedEpubBlockEntity?
    @Query("SELECT b.* FROM imported_epub_blocks b INNER JOIN imported_epubs e ON e.bookId = b.bookId AND e.editionId = b.editionId WHERE b.bookId = :bookId AND b.ordinal >= MAX(0, COALESCE((SELECT p.ordinal FROM imported_epub_blocks p WHERE p.bookId = b.bookId AND p.editionId = b.editionId AND p.startMs >= 0 AND p.startMs <= :position ORDER BY p.startMs DESC, CASE WHEN p.manualStartMs >= 0 THEN 0 ELSE 1 END, p.ordinal LIMIT 1) - 3, (SELECT f.ordinal FROM imported_epub_blocks f WHERE f.bookId = b.bookId AND f.editionId = b.editionId AND f.startMs >= 0 ORDER BY f.startMs, f.ordinal LIMIT 1), 0)) ORDER BY b.ordinal LIMIT 24")
    fun window(bookId: String, position: Long): Flow<List<ImportedEpubBlockEntity>>
    @Query("SELECT b.ordinal FROM imported_epub_blocks b INNER JOIN imported_epubs e ON e.bookId = b.bookId AND e.editionId = b.editionId WHERE b.bookId = :bookId AND b.startMs >= 0 AND b.startMs <= :position ORDER BY b.startMs DESC, b.ordinal LIMIT 1")
    suspend fun indexBefore(bookId: String, position: Long): Int?
    @Query("SELECT b.ordinal FROM imported_epub_blocks b INNER JOIN imported_epubs e ON e.bookId = b.bookId AND e.editionId = b.editionId WHERE b.bookId = :bookId AND b.startMs >= :position ORDER BY b.startMs, b.ordinal LIMIT 1")
    suspend fun indexAfter(bookId: String, position: Long): Int?
    @Query("SELECT MAX(b.ordinal) FROM imported_epub_blocks b INNER JOIN imported_epubs e ON e.bookId = b.bookId AND e.editionId = b.editionId WHERE b.bookId = :bookId AND b.startMs >= 0")
    suspend fun lastMapped(bookId: String): Int?
    @Query("DELETE FROM imported_epub_blocks WHERE bookId = :bookId AND editionId != :editionId") suspend fun prune(bookId: String, editionId: String)
    @Query("DELETE FROM imported_epub_blocks WHERE bookId = :bookId") suspend fun clearBlocks(bookId: String)
    @Query("DELETE FROM imported_epubs WHERE bookId = :bookId") suspend fun remove(bookId: String)
    @Query("UPDATE imported_epub_blocks SET anchorsJson = '[]', startMs = -1, endMs = -1, confidence = 0, automaticAnchorsJson = '[]', automaticConfidence = 0, manualStartMs = -1, manualEndMs = -1 WHERE bookId = :bookId")
    suspend fun clearAlignment(bookId: String)
    @Query("UPDATE imported_epub_blocks SET automaticAnchorsJson = '[]', automaticConfidence = 0, anchorsJson = CASE WHEN manualStartMs >= 0 THEN anchorsJson ELSE '[]' END, startMs = manualStartMs, endMs = manualEndMs, confidence = CASE WHEN manualStartMs >= 0 THEN 1 ELSE 0 END WHERE bookId = :bookId")
    suspend fun clearAutomaticAlignment(bookId: String)
    /** New text for the same verified audio: preserve manual timing and allow alignment immediately. */
    @Transaction suspend fun resetTranscriptMapping(bookId: String) {
        find(bookId)?.let { put(it.copy(state = "QUEUED", alignedRevision = -1, matchedSegments = 0, totalSegments = 0,
            matchedTokens = 0, totalTokens = 0, message = "Matching your EPUB to the audio…")) }
        clearAutomaticAlignment(bookId)
    }
    @Transaction suspend fun invalidateAudioMapping(bookId: String) {
        find(bookId)?.let { put(it.copy(state = "AUDIO_CHANGED", alignedRevision = -1, matchedSegments = 0, totalSegments = 0,
            matchedTokens = 0, totalTokens = 0, manualCount = 0, message = "The audio files changed. Your EPUB is kept and will be matched to the audio again.")) }
        clearAlignment(bookId)
    }
}
