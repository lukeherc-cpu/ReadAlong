package com.example.readalong.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.readalong.data.book.RoomBookRepository
import com.example.readalong.data.epub.GeneratedEpubRepository
import com.example.readalong.data.local.*
import com.example.readalong.data.reading.TranscriptRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class TranscriptCorrectionTest {
    @Test fun correctionsPreserveRawWordsAndTimesCanBeUndoneAndNeverApplyToChangedRecognition() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReadAlongDatabase::class.java).build()
        try {
            val id = RoomBookRepository(database, seedSamples = true).observeBooks().first().first().id
            TranscriptRepository(database).prepareDemo(id)
            val dao = database.readingDao()
            val raw = dao.segments(id).first()
            dao.putCorrection(TranscriptCorrectionEntity(id, raw.id, raw.text, "A gorilla beside the garden.", 0))
            assertEquals(raw, dao.segment(raw.id))
            assertEquals("A gorilla beside the garden.", dao.chapterSegments(id, raw.chapterId, 1, 0).single().text)
            val epub = GeneratedEpubRepository(context, database)
            epub.generate(id)
            val page = dao.generatedPaging(id).load(androidx.paging.PagingSource.LoadParams.Refresh<Int>(null, 40, false)) as androidx.paging.PagingSource.LoadResult.Page
            assertTrue(page.data.first().text.startsWith("A gorilla beside the garden."))
            assertEquals(raw.startMs, page.data.first().startMs)
            dao.putSegments(listOf(raw.copy(text = "Different recognition of the same time interval.")))
            assertEquals("Different recognition of the same time interval.", dao.chapterSegments(id, raw.chapterId, 1, 0).single().text)
            dao.putSegments(listOf(raw))
            dao.deleteCorrection(id, raw.id)
            assertEquals(raw.text, dao.chapterSegments(id, raw.chapterId, 1, 0).single().text)
        } finally { database.close() }
    }
}
