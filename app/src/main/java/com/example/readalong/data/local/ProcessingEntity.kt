package com.example.readalong.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "processing_jobs", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class ProcessingJobEntity(@PrimaryKey val bookId: String, val workId: String, val engineId: String,
    val state: String, val completedUnits: Int, val totalUnits: Int, val message: String?, val updatedAt: Long, val planJson: String? = null, val sourceFingerprint: String? = null, @ColumnInfo(defaultValue = "0") val queuePosition: Long = 0)

@Dao
interface ProcessingDao {
    @Query("SELECT * FROM processing_jobs WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<ProcessingJobEntity?>
    @Query("SELECT * FROM processing_jobs WHERE bookId = :bookId")
    suspend fun find(bookId: String): ProcessingJobEntity?
    @Query("SELECT * FROM processing_jobs WHERE state IN ('QUEUED', 'PROCESSING', 'PARTIAL_READY', 'GENERATING_EPUB')")
    suspend fun unfinished(): List<ProcessingJobEntity>
    @Query("SELECT * FROM processing_jobs WHERE state = 'WAITING_MODEL'")
    suspend fun waitingForModel(): List<ProcessingJobEntity>
    @Query("SELECT p.* FROM processing_jobs p JOIN books b ON b.id = p.bookId WHERE b.isDemo = 0 AND p.state IN ('WAITING_MODEL','QUEUED','PROCESSING','PARTIAL_READY','GENERATING_EPUB') ORDER BY p.queuePosition, b.addedAt, p.bookId")
    suspend fun orderedQueue(): List<ProcessingJobEntity>
    @Query("SELECT p.* FROM processing_jobs p JOIN books b ON b.id = p.bookId WHERE b.isDemo = 0 AND p.state != 'WAITING_SELECTION' ORDER BY p.queuePosition, b.addedAt, p.bookId")
    suspend fun registeredQueue(): List<ProcessingJobEntity>
    @Query("SELECT p.bookId FROM processing_jobs p JOIN books b ON b.id = p.bookId WHERE b.isDemo = 0 AND p.state IN ('QUEUED','PROCESSING','PARTIAL_READY','GENERATING_EPUB') ORDER BY p.queuePosition, b.addedAt, p.bookId LIMIT 1")
    suspend fun nextBook(): String?
    @Query("UPDATE processing_jobs SET queuePosition = :position WHERE bookId = :bookId")
    suspend fun position(bookId: String, position: Long)
    @Query("SELECT b.id AS bookId, b.title, COALESCE(p.state,'WAITING_SELECTION') AS state, COALESCE(p.completedUnits,0) AS completedUnits, COALESCE(p.totalUnits,0) AS totalUnits, COALESCE(p.queuePosition,0) AS queuePosition, b.addedAt, p.message, p.engineId, p.workId FROM books b LEFT JOIN processing_jobs p ON p.bookId = b.id WHERE b.isDemo = 0 ORDER BY p.queuePosition, b.addedAt, b.id")
    fun observeQueue(): Flow<List<PreparationQueueItem>>
    @Upsert suspend fun put(job: ProcessingJobEntity)
}

data class PreparationQueueItem(val bookId: String, val title: String, val state: String, val completedUnits: Int, val totalUnits: Int, val queuePosition: Long, val addedAt: Long, val message: String? = null, val engineId: String? = null, val workId: String? = null)
