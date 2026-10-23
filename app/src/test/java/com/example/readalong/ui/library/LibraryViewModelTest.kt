package com.example.readalong.ui.library

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.readalong.domain.model.Book
import com.example.readalong.testutil.FakeBookRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun libraryObservesRepositoryChangesWithMissingOptionalMetadata() = runTest(dispatcher) {
        val repository = FakeBookRepository()
        val books = repository.books
        val model = ViewModelProvider(store, LibraryViewModel.factory(repository))[LibraryViewModel::class.java]
        advanceUntilIdle()
        assertEquals(LibraryUiState.Ready(emptyList()), model.state.value)

        val book = Book(id = "local-book", title = "A local audiobook")
        books.value = listOf(book)
        advanceUntilIdle()
        assertEquals(LibraryUiState.Ready(listOf(book)), model.state.value)
    }

    @Test
    fun repositoryFailureIsVisibleAndRetryCanRecover() = runTest(dispatcher) {
        var fail = true
        val repository = object : FakeBookRepository() {
            override fun observeBooks() = flow<List<Book>> {
                if (fail) throw IOException("Database unavailable")
                emit(listOf(Book("recovered-book", "Recovered audiobook")))
            }
        }
        val model = ViewModelProvider(store, LibraryViewModel.factory(repository))[LibraryViewModel::class.java]
        advanceUntilIdle()
        assertEquals(LibraryUiState.Error, model.state.value)

        fail = false
        model.retry()
        advanceUntilIdle()
        assertEquals(
            LibraryUiState.Ready(listOf(Book("recovered-book", "Recovered audiobook"))),
            model.state.value,
        )
    }
}
