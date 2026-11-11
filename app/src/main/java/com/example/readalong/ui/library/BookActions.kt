package com.example.readalong.ui.library

import com.example.readalong.ui.components.userMessage

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.readalong.R
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
    /** Leaves nothing behind that a re-added copy of the book would pick up. The sheet is closed by
     * then, so a failure is shown as a toast instead of the sheet's error line. */
    fun delete(book: Book) = action {
        try {
            // Stopping work and playback is best effort: if either fails, the book must still be deleted.
            // A failure here used to end the delete silently, leaving the book and its progress in place.
            runCatching { app.processing.cancel(book.id) }.onFailure { android.util.Log.w("ReadAlong", "Delete: pausing the ReadAlong failed", it) }
            runCatching { app.playback.releaseBook(book.id) }.onFailure { android.util.Log.w("ReadAlong", "Delete: releasing playback failed", it) }
            app.processing.forgetAudioCaches(book.id)
            app.importedEpub.remove(book.id); repository.delete(book.id)
            app.processing.clearStoppedNotification(book.id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            android.widget.Toast.makeText(app, "Couldn't delete ${book.title}. Try again.", android.widget.Toast.LENGTH_LONG).show()
            throw failure
        }
    }
    fun epub(book: Book) = app.importedEpub.observe(book.id)
    /** Links the chosen EPUB to this book, the same way one found beside the audio is: it's matched to the ReadAlong as that's made. */
    fun addEpub(book: Book, uri: Uri, onAdded: () -> Unit) = action {
        app.importedEpub.import(book.id, uri)
        android.widget.Toast.makeText(app, "EPUB added. It's matched to the audio as the ReadAlong is made.", android.widget.Toast.LENGTH_LONG).show()
        onAdded()
    }
    fun dismissError() { error.value = null }
    private fun action(work: suspend () -> Unit) { viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true; error.value = null
        try { work() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error.value = failure.userMessage("Couldn't update this book. Try again.") }
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
    val epub by remember(book.id) { model.epub(book) }.collectAsStateWithLifecycle(null)
    val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.addEpub(book, uri, onDismiss) } }
    val cover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.edit(book, cover = uri) } }
    // Grouped rows: the EPUB (what a long-press is most often for), then editing, then status, then delete.
    // Status rows only offer a change that applies to this book.
    com.example.readalong.ui.components.ActionSheet(onDismiss, header = {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book, Modifier.width(64.dp), showTitle = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge)
                book.author?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        error?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
    }) {
        if (!book.isDemo) item { Column {
            com.example.readalong.ui.components.SheetRow(if (epub == null) "Add matching EPUB" else "Replace matching EPUB",
                { epubPicker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }, icon = R.drawable.ic_book, enabled = !busy)
            com.example.readalong.ui.components.SheetDivider()
        } }
        item { Column {
            com.example.readalong.ui.components.SheetRow("Edit title", { edit = "Title"; value = book.title }, icon = R.drawable.ic_edit, enabled = !busy)
            com.example.readalong.ui.components.SheetRow("Edit author", { edit = "Author"; value = book.author.orEmpty() }, icon = R.drawable.ic_edit, enabled = !busy)
            com.example.readalong.ui.components.SheetRow("Choose cover from files", { cover.launch(arrayOf("image/*")) }, icon = R.drawable.ic_image, enabled = !busy)
            com.example.readalong.ui.components.SheetRow("Find cover online", { internetCover = true }, icon = R.drawable.ic_globe, enabled = !busy)
            com.example.readalong.ui.components.SheetDivider()
        } }
        item { Column {
            if (book.section != com.example.readalong.domain.model.LibrarySection.NOT_STARTED)
                com.example.readalong.ui.components.SheetRow("Mark as not started", { model.notStarted(book); onDismiss() }, icon = R.drawable.ic_undo, enabled = !busy)
            if (!book.isFinished)
                com.example.readalong.ui.components.SheetRow("Mark as finished", { model.finished(book); onDismiss() }, icon = R.drawable.ic_check_circle, enabled = !busy)
            com.example.readalong.ui.components.SheetDivider()
            com.example.readalong.ui.components.SheetRow("Delete book", { delete = true }, icon = R.drawable.ic_trash, destructive = true, enabled = !busy)
        } }
    }
    if (internetCover) InternetCoverPicker(book.title, onDismiss = { internetCover = false }, onSave = { model.edit(book, cover = it); internetCover = false })
    edit?.let { field -> AlertDialog(onDismissRequest = { edit = null }, title = { Text("Edit ${field.lowercase()}") },
        text = { OutlinedTextField(value, { value = it.take(500) }, label = { Text(field) }, singleLine = true) },
        confirmButton = { TextButton(enabled = field != "Title" || value.isNotBlank(), onClick = {
            if (field == "Title") model.edit(book, title = value) else model.edit(book, author = value)
            edit = null
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } }) }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Delete this book?") },
        text = { Text("This removes the book, its ReadAlong and its listening position from ReadAlong. The audio and EPUB files stay in your folders.") },
        confirmButton = { TextButton(onClick = { model.delete(book); onDismiss() }) { Text("Delete book", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } })
}
