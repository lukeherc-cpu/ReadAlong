package com.example.readalong.ui.library

import com.example.readalong.domain.i18n.tr
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
            android.widget.Toast.makeText(app, tr("Couldn't delete ${book.title}. Try again."), android.widget.Toast.LENGTH_LONG).show()
            throw failure
        }
    }
    fun epub(book: Book) = app.importedEpub.observe(book.id)
    /** Links the chosen EPUB to this book, the same way one found beside the audio is: it's matched to the ReadAlong as that's made. */
    fun addEpub(book: Book, uri: Uri, onAdded: () -> Unit) = action {
        app.importedEpub.import(book.id, uri)
        android.widget.Toast.makeText(app, tr("EPUB added. It's matched to the audio as the ReadAlong is made."), android.widget.Toast.LENGTH_LONG).show()
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
    var internetCover by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val busy by model.busy.collectAsStateWithLifecycle()
    val collectionApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as? ReadAlongApplication
    val collections by remember(collectionApp) { collectionApp?.collections?.all ?: flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    var newCollection by rememberSaveable(book.id) { mutableStateOf(false) }
    val collectionScope = rememberCoroutineScope()
    val error by model.error.collectAsStateWithLifecycle()
    val epub by remember(book.id) { model.epub(book) }.collectAsStateWithLifecycle(null)
    val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.addEpub(book, uri, onDismiss) } }
    val cover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.edit(book, cover = uri) } }
    var sound by rememberSaveable(book.id) { mutableStateOf(false) }
    var collectionsOpen by rememberSaveable(book.id) { mutableStateOf(false) }
    var coverChoice by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val soundApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as? ReadAlongApplication
    // The book's own sound replaces this sheet, so closing it goes straight back to the library.
    if (sound && soundApp != null) { com.example.readalong.ui.sound.BookSoundSheet(soundApp, book.id, book.title, onDismiss); return }
    // The book's collections replace this sheet with a short checklist; closing it comes back here.
    if (collectionsOpen && collectionApp != null) {
        com.example.readalong.ui.components.ActionSheet({ collectionsOpen = false }, "Collections", book.title) {
            item { BookCollectionsSection(book, collections, onToggle = { collection, inside -> collectionScope.launch { collectionApp.collections.setInside(collection, book.id, inside) } },
                onNew = { newCollection = true }) }
        }
        if (newCollection) CollectionNameDialog("New collection", confirm = "Create", message = "${book.title} goes in it. You can add more books later.",
            onDismiss = { newCollection = false }) { name -> newCollection = false; collectionScope.launch { runCatching { collectionApp.collections.create(name, listOf(book.id)) } } }
        return
    }
    // Grouped rows, one line each where possible: the EPUB, sound and collections; then editing; then
    // status and delete. Status rows only offer a change that applies to this book.
    com.example.readalong.ui.components.ActionSheet(onDismiss, header = {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book, Modifier.width(64.dp), showTitle = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge)
                book.author?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        error?.let { Text(tr(it), Modifier.padding(horizontal = 24.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
    }) {
        // The matching EPUB first and tinted; everyday rows next; one-time edits and Sound and equalizer
        // at the bottom, with Delete last.
        if (!book.isDemo) item {
            com.example.readalong.ui.components.SheetRow(if (epub == null) "Add matching EPUB" else "Replace matching EPUB",
                { epubPicker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }, Modifier.testTag("book_add_epub"),
                icon = R.drawable.ic_book, enabled = !busy, highlight = true)
        }
        if (collectionApp != null) item {
            val inside = collections.filter { book.id in it.bookIds }.sortedBy { it.name.lowercase() }
            com.example.readalong.ui.components.SheetRow("Collections", { collectionsOpen = true }, Modifier.testTag("book_collections_row"), icon = R.drawable.ic_bookshelf,
                supporting = inside.takeIf { it.isNotEmpty() }?.joinToString { it.name }, enabled = !busy,
                trailing = { Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) })
        }
        item { Column {
            val finish = !book.isFinished
            val reset = book.section != com.example.readalong.domain.model.LibrarySection.NOT_STARTED
            if (finish || reset) com.example.readalong.ui.components.SheetDivider()
            if (finish)
                com.example.readalong.ui.components.SheetRow("Mark as finished", { model.finished(book); onDismiss() }, icon = R.drawable.ic_check_circle, enabled = !busy)
            if (reset)
                com.example.readalong.ui.components.SheetRow("Mark as not started", { model.notStarted(book); onDismiss() }, icon = R.drawable.ic_undo, enabled = !busy)
            com.example.readalong.ui.components.SheetDivider()
            com.example.readalong.ui.components.SheetRow("Edit title and author", { details = true }, Modifier.testTag("book_edit_details"), icon = R.drawable.ic_edit, enabled = !busy)
            com.example.readalong.ui.components.SheetRow("Change cover", { coverChoice = true }, Modifier.testTag("book_change_cover"), icon = R.drawable.ic_image,
                supporting = "From your files or online", enabled = !busy)
            if (!book.isDemo && soundApp != null) com.example.readalong.ui.components.SheetRow("Sound and equalizer", { sound = true }, Modifier.testTag("book_sound"),
                icon = R.drawable.ic_headphones, enabled = !busy)
            com.example.readalong.ui.components.SheetRow("Delete book", { delete = true }, icon = R.drawable.ic_trash, destructive = true, enabled = !busy)
        } }
    }
    if (coverChoice) com.example.readalong.ui.components.ActionSheet({ coverChoice = false }, "Change cover") {
        item { com.example.readalong.ui.components.SheetRow("From your files", { coverChoice = false; cover.launch(arrayOf("image/*")) }, icon = R.drawable.ic_image) }
        item { com.example.readalong.ui.components.SheetRow("Find online", { coverChoice = false; internetCover = true }, icon = R.drawable.ic_globe) }
    }
    if (details) {
        var title by rememberSaveable(book.id) { mutableStateOf(book.title) }
        var author by rememberSaveable(book.id) { mutableStateOf(book.author.orEmpty()) }
        AlertDialog(onDismissRequest = { details = false }, title = { Text(tr("Edit title and author")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it.take(500) }, label = { Text(tr("Title")) }, singleLine = true, modifier = Modifier.testTag("book_edit_title"))
                OutlinedTextField(author, { author = it.take(500) }, label = { Text(tr("Author")) }, singleLine = true, modifier = Modifier.testTag("book_edit_author"))
            } },
            confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
                model.edit(book, title = title.takeIf { it != book.title }, author = author.takeIf { it != book.author.orEmpty() }); details = false
            }) { Text(tr("Save")) } }, dismissButton = { TextButton(onClick = { details = false }) { Text(tr("Cancel")) } })
    }
    if (internetCover) InternetCoverPicker(book.title, onDismiss = { internetCover = false }, onSave = { model.edit(book, cover = it); internetCover = false })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text(tr("Delete this book?")) },
        text = { Text(tr("This removes the book, its ReadAlong and its listening position from ReadAlong. The audio and EPUB files stay in your folders.")) },
        confirmButton = { TextButton(onClick = { model.delete(book); onDismiss() }) { Text(tr("Delete book"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text(tr("Cancel")) } })
}
