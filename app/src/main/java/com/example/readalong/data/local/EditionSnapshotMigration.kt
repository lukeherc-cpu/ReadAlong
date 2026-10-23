package com.example.readalong.data.local

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/** Freeze existing edition timing while the original mapping is still available. */
class EditionSnapshotMigration : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("""UPDATE epub_paragraphs SET
            chapterId = COALESCE((SELECT chapterId FROM transcript_segments t WHERE t.id = segmentId AND t.bookId = epub_paragraphs.bookId), ''),
            startMs = COALESCE((SELECT startMs FROM transcript_segments t WHERE t.id = segmentId AND t.bookId = epub_paragraphs.bookId), 0),
            endMs = COALESCE((SELECT endMs FROM transcript_segments t WHERE t.id = segmentId AND t.bookId = epub_paragraphs.bookId), 0)""")
        db.execSQL("""INSERT OR IGNORE INTO epub_paragraphs (bookId, editionId, segmentId, text, href, chapterId, startMs, endMs, anchorsJson)
            SELECT p.bookId, 'legacy', p.segmentId, p.text, p.href, t.chapterId, t.startMs, t.endMs, '[]'
            FROM generated_paragraphs p JOIN transcript_segments t ON t.id = p.segmentId AND t.bookId = p.bookId""")
        db.execSQL("UPDATE readable_versions SET activeEditionId = 'legacy' WHERE activeEditionId IS NULL AND bookId IN (SELECT bookId FROM generated_paragraphs)")
    }
}
