package com.example.readalong.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.R
import com.example.readalong.playback.PlaybackChapter
import com.example.readalong.playback.clockTime
import com.example.readalong.ui.components.ActionSheet
import com.example.readalong.ui.components.SheetRow
import com.example.readalong.ui.reading.ReadingViewModel

/** Bookmarks as a sheet: add one at the current position up top, then each saved one with its chapter and time. */
@Composable
fun BookmarksDialog(model: ReadingViewModel, positionMs: Long, seek: (Long) -> Unit, close: () -> Unit, chapters: List<PlaybackChapter> = emptyList()) {
    val bookmarks by model.bookmarks.collectAsStateWithLifecycle()
    var addingAt by remember { mutableStateOf<Long?>(null) }
    var name by remember { mutableStateOf("") }
    fun where(ms: Long) = chapters.lastOrNull { it.startMs <= ms }?.let { "${it.title} · ${clockTime(ms)}" } ?: clockTime(ms)
    ActionSheet(close, "Bookmarks", when (bookmarks.size) { 0 -> null; 1 -> "1 saved"; else -> "${bookmarks.size} saved" }, header = {
        Button(onClick = { addingAt = positionMs; name = "" }, Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 12.dp).heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add bookmark at ${clockTime(positionMs)}")
        }
    }) {
        if (bookmarks.isEmpty()) item {
            Text("No bookmarks yet", Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(bookmarks, key = { it.id }) { bookmark ->
            SheetRow(bookmark.label ?: "Bookmark", { seek(bookmark.audioMs); close() }, icon = R.drawable.ic_bookmark,
                supporting = where(bookmark.audioMs), trailing = {
                    IconButton(onClick = { model.deleteBookmark(bookmark.id) }) {
                        Icon(painterResource(R.drawable.ic_trash), "Remove ${bookmark.label ?: "bookmark"}", Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                })
        }
    }
    addingAt?.let { at ->
        AlertDialog(onDismissRequest = { addingAt = null }, title = { Text("Name bookmark") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(where(at), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it.take(120) }, singleLine = true, label = { Text("Bookmark name") })
            }
        }, confirmButton = { TextButton(onClick = { model.bookmark(at, name.trim().ifEmpty { "Bookmark" }); addingAt = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { addingAt = null }) { Text("Cancel") } })
    }
}
