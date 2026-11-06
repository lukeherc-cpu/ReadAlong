package com.example.readalong.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** "Edit title and author…": the names shown for a book, shared with the phone (see BookInfoSync). */
@Composable
fun EditInfoDialog(app: AppState) {
    val id = app.editingInfo ?: return
    val book = app.library.book(id) ?: run { app.editingInfo = null; return }
    var title by remember(id) { mutableStateOf(book.title) }
    var author by remember(id) { mutableStateOf(book.author.orEmpty()) }
    val close = { app.editingInfo = null }
    AlertDialog(onDismissRequest = close, title = { Text("Edit title and author") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true, isError = title.isBlank())
            OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), label = { Text("Author") }, singleLine = true)
            Text("Your phone shows the same names after the next sync.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = {
        TextButton({
            val (t, a) = title to author
            app.scope.launch(Dispatchers.IO) { runCatching { app.bookInfo.edit(id, t, a); app.link.changed() }.onFailure { app.messages.tryEmit(it.message ?: "Couldn't save.") } }
            close()
        }, enabled = title.isNotBlank()) { Text("Save") }
    }, dismissButton = { TextButton(close) { Text("Cancel") } })
}
