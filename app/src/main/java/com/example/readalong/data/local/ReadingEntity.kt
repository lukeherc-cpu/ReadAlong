package com.example.readalong.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transcript_segments", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId", "startMs"], unique = true)])
data class TranscriptEntity(@PrimaryKey val id: String, val bookId: String, val chapterId: String,
    val startMs: Long, val endMs: Long, val text: String, val confidence: Float?, val wordsJson: String?, val anchorsJson: String? = null)

@Entity(tableName = "reading_progress", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class ReadingProgressEntity(@PrimaryKey val bookId: String, val segmentId: String, val audioMs: Long, val updatedAt: Long,
    val epubEditionId: String? = null, val epubOrdinal: Int? = null)

@Entity(tableName = "bookmarks", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class BookmarkEntity(@PrimaryKey val id: String, val bookId: String, val audioMs: Long,
    val segmentId: String?, val epubLocator: String?, val label: String?, val createdAt: Long)

@Entity(tableName = "readable_versions", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class ReadableVersionEntity(@PrimaryKey val bookId: String, val state: String, val message: String?,
    val completedChapters: Int, val totalChapters: Int?, val generatedUri: String?, val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val transcriptRevision: Long = 0,
    @ColumnInfo(defaultValue = "-1") val generatedRevision: Long = -1,
    val activeEditionId: String? = null, @ColumnInfo(defaultValue = "0") val generatedFormat: Int = 0)

@Dao
interface ReadingDao {
    @Query("SELECT t.id, t.bookId, t.chapterId, t.startMs, t.endMs, COALESCE(c.replacement, t.text) AS text, t.confidence, t.wordsJson, t.anchorsJson FROM transcript_segments t LEFT JOIN transcript_corrections c ON c.bookId = t.bookId AND c.segmentId = t.id AND c.originalText = t.text WHERE t.bookId = :bookId AND t.id IN (:ids)")
    suspend fun sourceSegments(bookId: String, ids: List<String>): List<TranscriptEntity>
    @Query("SELECT p.segmentId AS id, p.bookId, p.chapterId, p.startMs, p.endMs, p.text, NULL AS confidence, NULL AS wordsJson, p.anchorsJson FROM epub_paragraphs p INNER JOIN readable_versions v ON v.bookId = p.bookId AND v.activeEditionId = p.editionId WHERE p.bookId = :bookId AND p.endMs >= :position - 60000 AND p.startMs <= :position + 120000 ORDER BY p.startMs LIMIT 80")
    fun generatedWindow(bookId: String, position: Long): Flow<List<TranscriptEntity>>
    @Query("SELECT * FROM epub_paragraphs WHERE bookId = :bookId AND editionId = :editionId AND chapterId = :chapterId AND startMs < :endMs AND endMs >= :startMs ORDER BY startMs")
    suspend fun affectedParagraphs(bookId: String, editionId: String, chapterId: String, startMs: Long, endMs: Long): List<EpubParagraphEntity>
    @Query("SELECT * FROM epub_paragraphs WHERE bookId = :bookId AND editionId = :editionId AND chapterId = :chapterId AND endMs <= :position ORDER BY endMs DESC LIMIT 1")
    suspend fun previousParagraph(bookId: String, editionId: String, chapterId: String, position: Long): EpubParagraphEntity?
    @Query("DELETE FROM epub_paragraphs WHERE bookId = :bookId AND editionId = :editionId AND segmentId IN (:ids)")
    suspend fun deleteParagraphs(bookId: String, editionId: String, ids: List<String>)
    @Query("SELECT t.id, t.bookId, t.chapterId, t.startMs, t.endMs, COALESCE(c.replacement, t.text) AS text, t.confidence, t.wordsJson, t.anchorsJson FROM transcript_segments t LEFT JOIN transcript_corrections c ON c.bookId = t.bookId AND c.segmentId = t.id AND c.originalText = t.text WHERE t.bookId = :bookId AND t.chapterId = :chapterId AND t.endMs > :startMs AND t.startMs < :endMs ORDER BY t.startMs")
    suspend fun readingRange(bookId: String, chapterId: String, startMs: Long, endMs: Long): List<TranscriptEntity>
    @Query("SELECT p.segmentId AS id, p.bookId, p.chapterId, p.startMs, p.endMs, p.text, NULL AS confidence, NULL AS wordsJson, p.anchorsJson FROM epub_paragraphs p INNER JOIN readable_versions v ON v.bookId = p.bookId AND v.activeEditionId = p.editionId WHERE p.bookId = :bookId ORDER BY p.startMs")
    fun generatedPaging(bookId: String): androidx.paging.PagingSource<Int, TranscriptEntity>
    @Query("SELECT p.segmentId AS id, p.bookId, p.chapterId, p.startMs, p.endMs, p.text, NULL AS confidence, NULL AS wordsJson, p.anchorsJson FROM epub_paragraphs p WHERE p.bookId = :bookId AND p.editionId = :editionId ORDER BY p.startMs LIMIT :limit OFFSET :offset")
    suspend fun editionPage(bookId: String, editionId: String, limit: Int, offset: Int): List<TranscriptEntity>
    @Query("SELECT p.chapterId, MIN(p.startMs) AS startMs, '' AS title FROM epub_paragraphs p INNER JOIN readable_versions v ON v.bookId = p.bookId AND v.activeEditionId = p.editionId WHERE p.bookId = :bookId GROUP BY p.chapterId ORDER BY startMs")
    suspend fun generatedChapters(bookId: String): List<TranscriptChapter>
    @Query("SELECT COUNT(*) FROM epub_paragraphs p INNER JOIN readable_versions v ON v.bookId = p.bookId AND v.activeEditionId = p.editionId WHERE p.bookId = :bookId AND p.endMs <= :position")
    suspend fun generatedCountBefore(bookId: String, position: Long): Int
    @Query("SELECT p.startMs FROM epub_paragraphs p INNER JOIN readable_versions v ON v.bookId = p.bookId AND v.activeEditionId = p.editionId WHERE p.bookId = :bookId AND p.startMs <= :position AND p.endMs > :position LIMIT 1")
    suspend fun generatedAt(bookId: String, position: Long): Long?
    @Upsert suspend fun putCorrection(correction: TranscriptCorrectionEntity)
    @Query("SELECT * FROM transcript_corrections WHERE bookId = :bookId AND segmentId = :segmentId")
    suspend fun correction(bookId: String, segmentId: String): TranscriptCorrectionEntity?
    @Query("DELETE FROM transcript_corrections WHERE bookId = :bookId AND segmentId = :segmentId")
    suspend fun deleteCorrection(bookId: String, segmentId: String)
    @Upsert suspend fun putEdition(paragraphs: List<EpubParagraphEntity>)
    @Query("DELETE FROM epub_paragraphs WHERE bookId = :bookId AND editionId = :editionId")
    suspend fun deleteEdition(bookId: String, editionId: String)
    @Query("DELETE FROM epub_paragraphs WHERE bookId = :bookId AND editionId != :editionId")
    suspend fun pruneEditions(bookId: String, editionId: String)
    @Query("SELECT COUNT(*) FROM epub_paragraphs WHERE bookId = :bookId AND editionId = :editionId")
    suspend fun editionCount(bookId: String, editionId: String): Int
    @Upsert suspend fun putGenerated(paragraphs: List<GeneratedParagraphEntity>)
    @Query("DELETE FROM generated_paragraphs WHERE bookId = :bookId")
    suspend fun clearGenerated(bookId: String)
    @Query("SELECT t.id, t.bookId, t.chapterId, t.startMs, t.endMs, COALESCE(c.replacement, t.text) AS text, t.confidence, t.wordsJson, t.anchorsJson FROM transcript_segments t LEFT JOIN transcript_corrections c ON c.bookId = t.bookId AND c.segmentId = t.id AND c.originalText = t.text WHERE t.bookId = :bookId AND t.chapterId = :chapterId ORDER BY t.startMs LIMIT :limit OFFSET :offset")
    suspend fun chapterSegments(bookId: String, chapterId: String, limit: Int, offset: Int): List<TranscriptEntity>
    @Query("SELECT t.id, t.bookId, t.chapterId, t.startMs, t.endMs, COALESCE(c.replacement, t.text) AS text, t.confidence, t.wordsJson, t.anchorsJson FROM transcript_segments t LEFT JOIN transcript_corrections c ON c.bookId = t.bookId AND c.segmentId = t.id AND c.originalText = t.text WHERE t.bookId = :bookId ORDER BY t.startMs")
    fun paging(bookId: String): androidx.paging.PagingSource<Int, TranscriptEntity>
    @Query("SELECT chapterId, MIN(startMs) AS startMs, '' AS title FROM transcript_segments WHERE bookId = :bookId GROUP BY chapterId ORDER BY startMs")
    suspend fun chapters(bookId: String): List<TranscriptChapter>
    @Query("SELECT * FROM transcript_segments WHERE bookId = :bookId ORDER BY startMs LIMIT :limit OFFSET :offset")
    suspend fun segments(bookId: String, limit: Int = 80, offset: Int = 0): List<TranscriptEntity>
    @Query("SELECT t.id, t.bookId, t.chapterId, t.startMs, t.endMs, COALESCE(c.replacement, t.text) AS text, t.confidence, NULL AS wordsJson, t.anchorsJson FROM transcript_segments t LEFT JOIN transcript_corrections c ON c.bookId = t.bookId AND c.segmentId = t.id AND c.originalText = t.text WHERE t.bookId = :bookId ORDER BY t.startMs LIMIT :limit OFFSET :offset")
    suspend fun alignmentSegments(bookId: String, limit: Int, offset: Int): List<TranscriptEntity>
    @Query("SELECT t.id, t.bookId, t.chapterId, t.startMs, t.endMs, COALESCE(c.replacement, t.text) AS text, t.confidence, t.wordsJson, t.anchorsJson FROM transcript_segments t LEFT JOIN transcript_corrections c ON c.bookId = t.bookId AND c.segmentId = t.id AND c.originalText = t.text WHERE t.bookId = :bookId ORDER BY t.startMs LIMIT 80 OFFSET MAX(0, (SELECT COUNT(*) FROM transcript_segments WHERE bookId = :bookId AND endMs <= :position) - 20)")
    fun observeWindow(bookId: String, position: Long): Flow<List<TranscriptEntity>>
    @Query("SELECT * FROM transcript_segments WHERE bookId = :bookId AND startMs <= :position AND endMs > :position ORDER BY startMs DESC LIMIT 1")
    suspend fun at(bookId: String, position: Long): TranscriptEntity?
    @Query("SELECT * FROM transcript_segments WHERE bookId = :bookId AND startMs < :position AND endMs >= :position - 160 ORDER BY startMs DESC LIMIT 1")
    suspend fun precedingForSeek(bookId: String, position: Long): TranscriptEntity?
    @Query("SELECT COUNT(*) FROM transcript_segments WHERE bookId = :bookId AND startMs < :position")
    suspend fun countBefore(bookId: String, position: Long): Int
    @Query("SELECT COUNT(*) FROM transcript_segments WHERE bookId = :bookId")
    suspend fun count(bookId: String): Int
    @Query("SELECT * FROM transcript_segments WHERE id = :id")
    suspend fun segment(id: String): TranscriptEntity?
    @Upsert suspend fun putSegments(segments: List<TranscriptEntity>)
    @Query("DELETE FROM transcript_segments WHERE bookId = :bookId AND startMs < :endMs AND endMs > :startMs")
    suspend fun deleteRange(bookId: String, startMs: Long, endMs: Long)
    @Query("DELETE FROM transcript_segments WHERE bookId = :bookId")
    suspend fun clearTranscript(bookId: String)
    @Query("DELETE FROM transcript_corrections WHERE bookId = :bookId")
    suspend fun clearCorrections(bookId: String)
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun progress(bookId: String): ReadingProgressEntity?
    @Upsert suspend fun putProgress(progress: ReadingProgressEntity)
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY audioMs, createdAt")
    fun bookmarks(bookId: String): Flow<List<BookmarkEntity>>
    @Upsert suspend fun putBookmark(bookmark: BookmarkEntity)
    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: String)
    @Query("SELECT * FROM readable_versions WHERE bookId = :bookId")
    fun version(bookId: String): Flow<ReadableVersionEntity?>
    @Query("SELECT * FROM readable_versions WHERE bookId = :bookId")
    suspend fun findVersion(bookId: String): ReadableVersionEntity?
    @Upsert suspend fun putVersion(version: ReadableVersionEntity)
}

data class TranscriptChapter(val chapterId: String, val startMs: Long, val title: String = "")
