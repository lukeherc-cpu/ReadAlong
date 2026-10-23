package com.example.readalong.ui.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.readalong.domain.model.Book
import com.example.readalong.domain.repository.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

sealed interface BookUiState {
    data object Loading : BookUiState
    data class Ready(val book: Book) : BookUiState
    data object Missing : BookUiState
    data object Error : BookUiState
}

class BookViewModel(private val bookId: String, private val repository: BookRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<BookUiState>(BookUiState.Loading)
    val state = mutableState.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableUpdateFailed = MutableStateFlow(false)
    val updateFailed = mutableUpdateFailed.asStateFlow()
    private var observation: Job? = null

    init { retry() }

    fun retry() {
        observation?.cancel()
        mutableState.value = BookUiState.Loading
        observation = viewModelScope.launch {
            flow { emitAll(repository.observeBook(bookId)) }
                .catch { mutableState.value = BookUiState.Error }
                .collect { mutableState.value = it?.let(BookUiState::Ready) ?: BookUiState.Missing }
        }
    }

    fun setFinished(finished: Boolean) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        mutableUpdateFailed.value = false
        viewModelScope.launch {
            try {
                repository.setFinished(bookId, finished)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUpdateFailed.value = true
            } finally {
                mutableBusy.value = false
            }
        }
    }

    companion object {
        fun factory(bookId: String, repository: BookRepository) = viewModelFactory {
            initializer { BookViewModel(bookId, repository) }
        }
    }
}
