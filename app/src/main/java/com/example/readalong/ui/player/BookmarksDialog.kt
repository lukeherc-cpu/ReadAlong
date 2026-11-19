package com.example.readalong.ui.player

import com.example.readalong.domain.i18n.tr
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
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

/**
 * Bookmarks as a sheet: add one at the current position up top, then each saved one with its chapter and
 * time. Given a book id in [history], a second tab holds the book's Listening history (recent jumps), so
 * both ways back to a place live behind the one Bookmark button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksDialog(model: ReadingViewModel, positionMs: Long, seek: (Long) -> Unit, close: () -> Unit, chapters: List<PlaybackChapter> = emptyList(),
    history: String? = null, chapterTitle: String = "") {
    val bookmarks by model.bookmarks.collectAsStateWithLifecycle()
    var addingAt by remember { mutableStateOf<Long?>(null) }
    var name by remember { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val jumps by rememberHistory(history ?: "")
    fun where(ms: Long) = chapters.lastOrNull { it.startMs <= ms }?.let { "${it.title} · ${clockTime(ms)}" } ?: clockTime(ms)
    ActionSheet(close, "Bookmarks", header = {
        if (history != null) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
            SegmentedButton(!showHistory, { showHistory = false }, SegmentedButtonDefaults.itemShape(0, 2), Modifier.testTag("bookmarks_saved_tab"), icon = {}) {
                Text(tr(if (bookmarks.isEmpty()) "Saved" else "Saved · ${bookmarks.size}"))
            }
            SegmentedButton(showHistory, { showHistory = true }, SegmentedButtonDefaults.itemShape(1, 2), Modifier.testTag("bookmarks_history_tab"), icon = {}) { Text(tr("History")) }
        }
        if (!showHistory) Button(onClick = { addingAt = positionMs; name = "" }, Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 12.dp).heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("Add bookmark at ${clockTime(positionMs)}"))
        }
    }) {
        if (showHistory) { historyItems(jumps, positionMs, chapterTitle, seek, close); return@ActionSheet }
        if (bookmarks.isEmpty()) item {
            Text(tr("No bookmarks yet"), Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(bookmarks, key = { it.id }) { bookmark ->
            SheetRow(bookmark.label ?: "Bookmark", { seek(bookmark.audioMs); close() }, icon = R.drawable.ic_bookmark,
                supporting = where(bookmark.audioMs), trailing = {
                    IconButton(onClick = { model.deleteBookmark(bookmark.id) }) {
                        Icon(painterResource(R.drawable.ic_trash), tr("Remove ${bookmark.label ?: "bookmark"}"), Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                })
        }
    }
    addingAt?.let { at ->
        AlertDialog(onDismissRequest = { addingAt = null }, title = { Text(tr("Name bookmark")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(where(at)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it.take(120) }, singleLine = true, label = { Text(tr("Bookmark name")) })
            }
        }, confirmButton = { TextButton(onClick = { model.bookmark(at, name.trim().ifEmpty { "Bookmark" }); addingAt = null }) { Text(tr("Save")) } },
            dismissButton = { TextButton(onClick = { addingAt = null }) { Text(tr("Cancel")) } })
    }
}
