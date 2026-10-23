package com.example.readalong.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.data.local.ReadAlongDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EditionSnapshotMigrationTest {
    @Test fun versionSixEditionKeepsItsOriginalTimesAfterTranscriptReplacement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "snapshot-migration-${System.nanoTime()}.db"
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.example.readalong.data.local.ReadAlongDatabase/6.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) legacy.execSQL(setup.getString(i))
            legacy.execSQL("INSERT INTO books VALUES ('book', 'Original test book', NULL, NULL, NULL, 900000, 42, 0, 0, 'AVAILABLE')")
            legacy.execSQL("INSERT INTO transcript_segments VALUES ('segment', 'book', 'chapter', 12000, 24000, 'Original recognition', NULL, NULL)")
            legacy.execSQL("INSERT INTO epub_paragraphs VALUES ('book', 'edition', 'segment', 'Saved edition words ♪', 'chapter.xhtml#segment')")
            legacy.execSQL("INSERT INTO readable_versions (bookId, state, completedChapters, totalChapters, generatedUri, updatedAt, transcriptRevision, generatedRevision, activeEditionId) VALUES ('book', 'READY', 1, 1, 'content://saved/edition', 42, 2, 2, 'edition')")
            legacy.version = 6
        }
        val db = Room.databaseBuilder(context, ReadAlongDatabase::class.java, name).build()
        try {
            db.readingDao().clearTranscript("book")
            val page = db.readingDao().generatedPaging("book").load(androidx.paging.PagingSource.LoadParams.Refresh<Int>(null, 40, false)) as androidx.paging.PagingSource.LoadResult.Page
            assertEquals("Saved edition words ♪", page.data.single().text)
            assertEquals(12000L, page.data.single().startMs)
            assertEquals(24000L, page.data.single().endMs)
            assertEquals("chapter", page.data.single().chapterId)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
