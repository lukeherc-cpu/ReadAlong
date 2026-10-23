package com.example.readalong.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.ReadAlongApplication
import com.example.readalong.domain.model.Book
import com.example.readalong.ui.components.BookCover
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class BookActionsViewModel(private val app: ReadAlongApplication) : ViewModel() {
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private val repository = com.example.readalong.data.book.BookActionsRepository(app, app.database)
    fun edit(book: Book, title: String? = null, author: String? = null, cover: Uri? = null) = action { repository.edit(book.id, title, author, cover) }
    fun finished(book: Book) = action { app.bookRepository.setFinished(book.id, true) }
    fun notStarted(book: Book) = action { app.playback.releaseBook(book.id); repository.notStarted(book.id) }
    fun delete(book: Book) = action {
        app.processing.cancel(book.id); app.playback.releaseBook(book.id)
        app.importedEpub.remove(book.id); repository.delete(book.id)
    }
    fun dismissError() { error.value = null }
    private fun action(work: suspend () -> Unit) { viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true; error.value = null
        try { work() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error.value = failure.message ?: "Couldn't update this book. Please retry." }
        finally { busy.value = false }
    } }
    companion object { fun factory(app: ReadAlongApplication) = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = BookActionsViewModel(app) as T
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookActions(book: Book, model: BookActionsViewModel, onDismiss: () -> Unit) {
    var edit by rememberSaveable(book.id) { mutableStateOf<String?>(null) }
    var value by rememberSaveable(book.id) { mutableStateOf("") }
    var internetCover by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val cover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.edit(book, cover = uri) } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BookCover(book, Modifier.width(64.dp), showTitle = false)
                Column(Modifier.weight(1f)) { Text(book.title, style = MaterialTheme.typography.titleLarge); Text(book.author.orEmpty(), style = MaterialTheme.typography.bodySmall) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            listOf("Title", "Author", "Cover from file system", "Cover from internet", "Mark as not started", "Mark as finished", "Delete book").forEach { label ->
                TextButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    when (label) {
                        "Title", "Author" -> { edit = label; value = if (label == "Title") book.title else book.author.orEmpty() }
                        "Cover from file system" -> cover.launch(arrayOf("image/*"))
                        "Cover from internet" -> internetCover = true
                        "Delete book" -> delete = true
                        "Mark as not started" -> { model.notStarted(book); onDismiss() }
                        "Mark as finished" -> { model.finished(book); onDismiss() }
                    }
                }) { Text(label, Modifier.fillMaxWidth(), color = if (label == "Delete book") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            }
        }
    }
    if (internetCover) InternetCoverPicker(book.title, onDismiss = { internetCover = false }, onSave = { model.edit(book, cover = it); internetCover = false })
    edit?.let { field -> AlertDialog(onDismissRequest = { edit = null }, title = { Text("Edit ${field.lowercase()}") },
        text = { OutlinedTextField(value, { value = it.take(500) }, label = { Text(field) }, singleLine = true) },
        confirmButton = { TextButton(enabled = field != "Title" || value.isNotBlank(), onClick = {
            if (field == "Title") model.edit(book, title = value) else model.edit(book, author = value)
            edit = null
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } }) }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Delete this book?") },
        text = { Text("Remove the book, saved positions and its prepared text from ReadAlong. Original audio and EPUB files stay in your folders.") },
        confirmButton = { TextButton(onClick = { model.delete(book); onDismiss() }) { Text("Delete book") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } })
}
