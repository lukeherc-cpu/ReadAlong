package com.example.readalong.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.data.local.ReadAlongDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DatabaseMigrationTest {
    @Test fun versionNineAddsManualSyncWithoutChangingExistingMappings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "queue-migration-${System.nanoTime()}.db"
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("com.example.readalong.data.local.ReadAlongDatabase/9.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO books (id,title,durationMs,addedAt,coverStyle,isDemo,mediaState) VALUES ('v8','Existing book',75000,1,0,0,'AVAILABLE')")
            old.execSQL("INSERT INTO book_progress (bookId,audioPositionMs,isFinished,lastPlayedAt) VALUES ('v8',42123,0,1)")
            old.execSQL("INSERT INTO reading_progress VALUES ('v8','source',12000,1,'epub',2)")
            old.execSQL("INSERT INTO processing_jobs (bookId,workId,engineId,state,completedUnits,totalUnits,updatedAt,planJson,sourceFingerprint) VALUES ('v8','checkpoint','whisper-base.en-v1','QUEUED',1,3,1,'[]','source')")
            old.execSQL("INSERT INTO imported_epubs VALUES ('v8','epub','content://saved/epub','Original EPUB','PARTIAL','Matched','AUTO',1,1,3,6,30,1)")
            old.execSQL("INSERT INTO imported_epub_blocks VALUES ('v8','epub',2,0,'chapter.xhtml','Original ♪ text','P','[{\"fontFamily\":\"OriginalSerif\"}]',0,'[]',12000,18000,0.99)")
            old.version = 9
        }
        val db = Room.databaseBuilder(context, ReadAlongDatabase::class.java, name).build()
        try {
            assertEquals(42123L, db.bookDao().progress("v8")?.audioPositionMs)
            assertEquals("epub", db.readingDao().progress("v8")?.epubEditionId)
            assertEquals(2, db.readingDao().progress("v8")?.epubOrdinal)
            assertEquals(0L, db.processingDao().find("v8")?.queuePosition)
            assertEquals(1, db.processingDao().find("v8")?.completedUnits)
            assertEquals("AUTO", db.importedEpubDao().find("v8")?.preference)
            val block = db.importedEpubDao().block("v8", "epub", 2)!!
            assertEquals("Original ♪ text", block.text)
            assertTrue(block.stylesJson.contains("OriginalSerif"))
            assertEquals(12000L, block.startMs)
            assertEquals(-1L, block.manualStartMs)
            assertEquals(-1L, block.manualEndMs)
            assertNull(block.automaticAnchorsJson)
            assertNull(block.automaticConfidence)
            assertEquals(0, db.importedEpubDao().find("v8")!!.manualCount)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun versionEightKeepsEpubFontsLocatorsAndCheckpointWhenQueueColumnIsAdded() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "queue-migration-${System.nanoTime()}.db"
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("com.example.readalong.data.local.ReadAlongDatabase/8.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO books (id,title,durationMs,addedAt,coverStyle,isDemo,mediaState) VALUES ('v8','Existing book',75000,1,0,0,'AVAILABLE')")
            old.execSQL("INSERT INTO book_progress (bookId,audioPositionMs,isFinished,lastPlayedAt) VALUES ('v8',42123,0,1)")
            old.execSQL("INSERT INTO reading_progress VALUES ('v8','source',12000,1,'epub',2)")
            old.execSQL("INSERT INTO processing_jobs (bookId,workId,engineId,state,completedUnits,totalUnits,updatedAt,planJson,sourceFingerprint) VALUES ('v8','checkpoint','whisper-base.en-v1','QUEUED',1,3,1,'[]','source')")
            old.execSQL("INSERT INTO imported_epubs VALUES ('v8','epub','content://saved/epub','Original EPUB','PARTIAL','Matched','AUTO',1,1,3,6,30,1)")
            old.execSQL("INSERT INTO imported_epub_blocks VALUES ('v8','epub',2,0,'chapter.xhtml','Original ♪ text','P','[{\"fontFamily\":\"OriginalSerif\"}]',0,'[]',12000,18000,0.99)")
            old.version = 8
        }
        val db = Room.databaseBuilder(context, ReadAlongDatabase::class.java, name).build()
        try {
            assertEquals(42123L, db.bookDao().progress("v8")?.audioPositionMs)
            assertEquals("epub", db.readingDao().progress("v8")?.epubEditionId)
            assertEquals(2, db.readingDao().progress("v8")?.epubOrdinal)
            assertEquals(0L, db.processingDao().find("v8")?.queuePosition)
            assertEquals(1, db.processingDao().find("v8")?.completedUnits)
            assertEquals("AUTO", db.importedEpubDao().find("v8")?.preference)
            val block = db.importedEpubDao().block("v8", "epub", 2)!!
            assertEquals("Original ♪ text", block.text)
            assertTrue(block.stylesJson.contains("OriginalSerif"))
            assertEquals(12000L, block.startMs)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun versionOneLibraryUpgradesWithoutLosingProgress() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${System.nanoTime()}.db"
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val schema = JSONObject(assets.open("com.example.readalong.data.local.ReadAlongDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) legacy.execSQL(setup.getString(i))
            legacy.execSQL("INSERT INTO books VALUES ('legacy', 'Existing audiobook', NULL, NULL, NULL, 900000, 42, 0, 0)")
            legacy.execSQL("INSERT INTO book_progress VALUES ('legacy', 123456, 1, 99)")
            legacy.version = 1
        }
        val upgraded = Room.databaseBuilder(context, ReadAlongDatabase::class.java, name).build()
        try {
            assertEquals("Existing audiobook", upgraded.bookDao().findBook("legacy")?.title)
            val progress = upgraded.bookDao().progress("legacy")!!
            assertEquals(123_456L, progress.audioPositionMs)
            assertTrue(progress.isFinished)
            assertEquals("NONE", upgraded.bookDao().findBook("legacy")?.mediaState)
            assertTrue(upgraded.mediaDao().audioFiles("legacy").isEmpty())
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
    @Test fun versionFourGeneratedTextAndBookmarksRemainReadableAfterUpgrade() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "edition-migration-${System.nanoTime()}.db"
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val schema = JSONObject(assets.open("com.example.readalong.data.local.ReadAlongDatabase/4.json").bufferedReader().use { it.readText() }).getJSONObject("database")
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
            legacy.execSQL("INSERT INTO books VALUES ('legacy-edition', 'Existing audiobook', NULL, NULL, NULL, 900000, 42, 0, 0, 'AVAILABLE')")
            legacy.execSQL("INSERT INTO book_progress VALUES ('legacy-edition', 123456, 0, 99)")
            legacy.execSQL("INSERT INTO transcript_segments VALUES ('segment', 'legacy-edition', 'chapter', 100, 200, 'Original text', NULL, NULL)")
            legacy.execSQL("INSERT INTO generated_paragraphs VALUES ('legacy-edition', 'segment', '♪ ♫', 'chapter.xhtml#segment')")
            legacy.execSQL("INSERT INTO readable_versions VALUES ('legacy-edition', 'READY', NULL, 1, 1, 'content://saved/edition', 42)")
            legacy.execSQL("INSERT INTO bookmarks VALUES ('bookmark', 'legacy-edition', 100, 'segment', 'chapter.xhtml#segment', NULL, 42)")
            legacy.version = 4
        }
        val upgraded = Room.databaseBuilder(context, ReadAlongDatabase::class.java, name).build()
        try {
            val version = upgraded.readingDao().findVersion("legacy-edition")!!
            assertEquals("content://saved/edition", version.generatedUri)
            assertEquals("legacy", version.activeEditionId)
            assertEquals(0L, version.transcriptRevision)
            assertEquals(-1L, version.generatedRevision)
            val page = upgraded.readingDao().generatedPaging("legacy-edition").load(
                androidx.paging.PagingSource.LoadParams.Refresh<Int>(null, 40, false)) as androidx.paging.PagingSource.LoadResult.Page
            assertEquals("♪ ♫", page.data.single().text)
            assertEquals(123456L, upgraded.bookDao().progress("legacy-edition")?.audioPositionMs)
            assertEquals("chapter.xhtml#segment", upgraded.readingDao().bookmarks("legacy-edition").first().single().epubLocator)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    @Test fun versionSevenUpgradesKeepingAudioCheckpointEditionAndReadingPosition() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "phase14-migration-${System.nanoTime()}.db"
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("com.example.readalong.data.local.ReadAlongDatabase/7.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO books (id,title,durationMs,addedAt,coverStyle,isDemo,mediaState) VALUES ('v7','Existing book',75000,1,0,0,'AVAILABLE')")
            old.execSQL("INSERT INTO book_progress (bookId,audioPositionMs,isFinished,lastPlayedAt) VALUES ('v7',42123,0,1)")
            old.execSQL("INSERT INTO reading_progress VALUES ('v7','s1',12000,1)")
            old.execSQL("INSERT INTO readable_versions (bookId,state,completedChapters,totalChapters,generatedUri,updatedAt,activeEditionId) VALUES ('v7','PARTIAL_READY',0,1,'content://saved/edition',1,'saved')")
            old.execSQL("INSERT INTO processing_jobs (bookId,workId,engineId,state,completedUnits,totalUnits,updatedAt,planJson,sourceFingerprint) VALUES ('v7','checkpoint','whisper-base-en','QUEUED',1,3,1,'[]','source')")
            old.version = 7
        }
        val db = Room.databaseBuilder(context, ReadAlongDatabase::class.java, name).build()
        try {
            assertEquals(42123L, db.bookDao().progress("v7")?.audioPositionMs)
            assertEquals(12000L, db.readingDao().progress("v7")?.audioMs)
            assertNull(db.readingDao().progress("v7")?.epubEditionId)
            assertEquals("saved", db.readingDao().findVersion("v7")?.activeEditionId)
            assertEquals(1, db.processingDao().find("v7")?.completedUnits)
            assertEquals("[]", db.processingDao().find("v7")?.planJson)
            assertTrue(db.importedEpubDao().all().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

}
