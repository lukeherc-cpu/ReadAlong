package com.example.readalong.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.readalong.domain.model.Book
import com.example.readalong.domain.repository.BookRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Ready(val books: List<Book>) : LibraryUiState
    data object Error : LibraryUiState
}

class LibraryViewModel(private val repository: BookRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()
    private var observation: Job? = null

    init {
        retry()
    }

    fun retry() {
        observation?.cancel()
        mutableState.value = LibraryUiState.Loading
        observation = viewModelScope.launch {
            flow { emitAll(repository.observeBooks()) }
                .catch { mutableState.value = LibraryUiState.Error }
                .collect { mutableState.value = LibraryUiState.Ready(it) }
        }
    }

    companion object {
        fun factory(repository: BookRepository) = viewModelFactory {
            initializer { LibraryViewModel(repository) }
        }
    }
}
