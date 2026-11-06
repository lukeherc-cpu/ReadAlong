package com.example.readalong.data.importing

import com.example.readalong.data.local.FolderBook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Library folders the user has removed whose books are still being cleaned up. Removing a folder
 * hides it and its books straight away; the clean-up (stopping ReadAlongs, clearing progress and
 * derived files) runs in the background and may first wait for a scan of that folder to finish.
 */
object FolderRemovals {
    private val pending = MutableStateFlow(emptySet<String>())
    /** Folder uris being removed. */
    val folders: StateFlow<Set<String>> = pending.asStateFlow()
    fun start(uri: String) = pending.update { it + uri }
    fun finish(uri: String) = pending.update { it - uri }

    /** Ids of the books in folders being removed, read from [folderBooks] only while there are any. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun hiddenBooks(folderBooks: () -> Flow<List<FolderBook>>): Flow<Set<String>> = folders.flatMapLatest { uris ->
        if (uris.isEmpty()) flowOf(emptySet())
        else folderBooks().map { rows -> rows.filter { it.folderUri in uris }.mapTo(HashSet()) { it.bookId } }
    }
}
