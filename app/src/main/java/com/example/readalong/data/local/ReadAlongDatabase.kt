package com.example.readalong.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class, BookProgressEntity::class, LibraryMetadataEntity::class, AudioFileEntity::class, FolderEntity::class, AudioChapterEntity::class, TranscriptEntity::class, ReadingProgressEntity::class, BookmarkEntity::class, ReadableVersionEntity::class, GeneratedParagraphEntity::class, EpubParagraphEntity::class, ProcessingJobEntity::class, TranscriptCorrectionEntity::class, ImportedEpubEntity::class, ImportedEpubBlockEntity::class],
    version = 10,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7, spec = EditionSnapshotMigration::class), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10)],
    exportSchema = true,
)
abstract class ReadAlongDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun mediaDao(): MediaDao
    abstract fun readingDao(): ReadingDao
    abstract fun processingDao(): ProcessingDao
    abstract fun importedEpubDao(): ImportedEpubDao

    companion object {
        fun create(context: Context): ReadAlongDatabase = Room.databaseBuilder(
            context.applicationContext,
            ReadAlongDatabase::class.java,
            "readalong.db",
        ).build()
        // Future schema changes require explicit, tested migrations. No destructive fallback.
    }
}
