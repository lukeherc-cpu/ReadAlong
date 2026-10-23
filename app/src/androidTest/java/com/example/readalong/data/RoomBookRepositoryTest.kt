package com.example.readalong.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.example.readalong.data.book.RoomBookRepository
import com.example.readalong.data.local.ReadAlongDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBookRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "test-library-${System.nanoTime()}.db"
    private var database: ReadAlongDatabase? = null

    private fun open(): RoomBookRepository {
        val db = Room.databaseBuilder(context, ReadAlongDatabase::class.java, databaseName).build()
        database = db
        return RoomBookRepository(db, seedSamples = true)
    }

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun progressAndCompletionSurviveReopenWithoutResettingSamples() = runBlocking {
        withTimeout(10_000) {
            var repository = open()
            assertEquals(6, repository.observeBooks().first().size)
            repository.updateListeningPosition("demo-night-orchard", 1_234_567)
            repository.setFinished("demo-night-orchard", true)
            database?.close()
            repository = open()
            val book = requireNotNull(repository.observeBook("demo-night-orchard").first())
            assertEquals(1_234_567L, book.positionMs)
            assertTrue(book.isFinished)
            assertNotNull(book.lastPlayedAt)
            assertTrue(book.isDemo)
            assertEquals(6, repository.observeBooks().first().size)
            repository.setFinished(book.id, false)
            assertEquals(1_234_567L, repository.observeBook(book.id).first()?.positionMs)
        }
    }

    @Test
    fun positionIsBoundedWithoutMutatingOtherBooks() = runBlocking {
        withTimeout(10_000) {
            val repository = open()
            val books = repository.observeBooks().first()
            val target = books.first()
            val other = books.last()
            repository.updateListeningPosition(target.id, Long.MAX_VALUE)
            assertEquals(target.durationMs, repository.observeBook(target.id).first()?.positionMs)
            assertEquals(other.positionMs, repository.observeBook(other.id).first()?.positionMs)
            repository.updateListeningPosition(target.id, -100)
            assertEquals(0L, repository.observeBook(target.id).first()?.positionMs)
        }
    }
}
