package com.example.readalong.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.R
import com.example.readalong.playback.clockTime
import com.example.readalong.ui.reading.ReadingViewModel

@Composable
fun BookmarksDialog(model: ReadingViewModel, positionMs: Long, seek: (Long) -> Unit, close: () -> Unit) {
    val bookmarks by model.bookmarks.collectAsStateWithLifecycle()
    var addingAt by remember { mutableStateOf<Long?>(null) }
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("Bookmarks") }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            if (bookmarks.isEmpty()) item { Text("No bookmarks yet. Add one at the current audio position.") }
            items(bookmarks, key = { it.id }) { bookmark ->
                Row {
                    TextButton(onClick = { seek(bookmark.audioMs); close() }, modifier = Modifier.weight(1f)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(bookmark.label ?: "Bookmark")
                            Text(clockTime(bookmark.audioMs), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    TextButton(onClick = { model.deleteBookmark(bookmark.id) }) { Text("Remove") }
                }
            }
        }
    }, confirmButton = {
        FilledIconButton(onClick = { addingAt = positionMs; name = "" }) { Icon(painterResource(R.drawable.ic_add), "Add bookmark") }
    }, dismissButton = { TextButton(onClick = close) { Text("Close") } })
    addingAt?.let { at ->
        AlertDialog(onDismissRequest = { addingAt = null }, title = { Text("Name bookmark") }, text = {
            Column {
                Text(clockTime(at))
                OutlinedTextField(name, { name = it.take(120) }, singleLine = true, label = { Text("Bookmark name") })
            }
        }, confirmButton = { TextButton(onClick = { model.bookmark(at, name.trim().ifEmpty { "Bookmark" }); addingAt = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { addingAt = null }) { Text("Cancel") } })
    }
}
