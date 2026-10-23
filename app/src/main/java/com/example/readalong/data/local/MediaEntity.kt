package com.example.readalong.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "audio_files", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId"), Index(value = ["uri"], unique = true)])
data class AudioFileEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val uri: String,
    val name: String,
    val ordinal: Int,
    val durationMs: Long?,
    val folderUri: String?,
)

@Entity(tableName = "audiobook_folders")
data class FolderEntity(@PrimaryKey val uri: String, val name: String, val mode: String,
    val lastScanAt: Long?, val error: String?)

@Entity(tableName = "audio_chapters", foreignKeys = [ForeignKey(entity = AudioFileEntity::class,
    parentColumns = ["id"], childColumns = ["fileId"], onDelete = ForeignKey.CASCADE)], indices = [Index("fileId")])
data class AudioChapterEntity(@PrimaryKey val id: String, val fileId: String, val title: String,
    val ordinal: Int, val startMs: Long, val endMs: Long?)

@Dao
interface MediaDao {
    @Query("SELECT c.* FROM audio_chapters c INNER JOIN audio_files f ON f.id = c.fileId WHERE f.bookId = :bookId ORDER BY f.ordinal, c.ordinal")
    suspend fun chapters(bookId: String): List<AudioChapterEntity>
    @Upsert suspend fun putChapters(chapters: List<AudioChapterEntity>)
    @Query("SELECT * FROM audio_files WHERE bookId = :bookId ORDER BY ordinal, id")
    suspend fun audioFiles(bookId: String): List<AudioFileEntity>
    @Query("SELECT * FROM audio_files WHERE uri = :uri LIMIT 1")
    suspend fun audioFile(uri: String): AudioFileEntity?
    @Query("SELECT * FROM audio_files")
    suspend fun allAudio(): List<AudioFileEntity>
    @Query("SELECT * FROM audiobook_folders")
    suspend fun savedFolders(): List<FolderEntity>
    @Upsert suspend fun putAudio(files: List<AudioFileEntity>)
    @Query("DELETE FROM audio_files WHERE bookId = :bookId")
    suspend fun clearAudio(bookId: String)
    @Query("SELECT DISTINCT bookId FROM audio_files WHERE folderUri = :uri")
    suspend fun folderBooks(uri: String): List<String>
    @Query("UPDATE books SET mediaState = :state WHERE id = :bookId")
    suspend fun mediaState(bookId: String, state: String)
    @Query("UPDATE audio_files SET durationMs = :duration WHERE id = :id")
    suspend fun duration(id: String, duration: Long)
    @Query("SELECT * FROM audiobook_folders ORDER BY name COLLATE NOCASE")
    fun observeFolders(): Flow<List<FolderEntity>>
    @Query("SELECT * FROM audiobook_folders WHERE uri = :uri")
    suspend fun folder(uri: String): FolderEntity?
    @Upsert suspend fun putFolder(folder: FolderEntity)
    @Query("DELETE FROM audiobook_folders WHERE uri = :uri")
    suspend fun removeFolder(uri: String)
}
