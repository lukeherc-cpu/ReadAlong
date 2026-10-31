package com.example.readalong.ui.importing

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.data.importing.FolderMode

/**
 * Add audiobooks: one folder picker for a single book or a whole library. The folder is looked at
 * first, and [AddPreviewDialog] shows what was found before anything is added, so a library can't
 * be added as one giant book by mistake. Returns the launcher; [origin] names the calling screen so
 * only its own preview is shown.
 */
@Composable
fun rememberAddAudiobooks(model: ImportViewModel, origin: String, onAdded: () -> Unit = {}): () -> Unit {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { model.previewFolder(it, origin) }
    }
    AddPreviewDialog(model, origin, onAdded)
    return { picker.launch(null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPreviewDialog(model: ImportViewModel, origin: String, onAdded: () -> Unit = {}) {
    val preview by model.preview.collectAsStateWithLifecycle()
    val shown = preview?.takeIf { it.origin == origin }?.folder ?: return
    // Keyed by folder: a new choice starts from the new folder's suggestion.
    var mode by remember(shown) { mutableStateOf(shown.suggested) }
    val deleted = if (mode == FolderMode.LIBRARY) shown.deletedBooks else if (shown.deletedAsOneBook) 1 else 0
    val everythingDeleted = mode == FolderMode.LIBRARY && shown.deletedBooks == shown.books.size || mode == FolderMode.ONE_BOOK && shown.deletedAsOneBook
    // Bring back deleted books by default only when nothing else would be added; otherwise ask.
    var restore by remember(shown, mode) { mutableStateOf(everythingDeleted) }

    if (shown.emptyMessage != null) {
        com.example.readalong.ui.components.IllustratedDialog("No audiobooks found", model::dismissPreview, actions = {
            TextButton(onClick = model::dismissPreview) { Text("OK") }
        }) { Text(shown.emptyMessage, style = MaterialTheme.typography.bodyLarge) }
        return
    }
    val count = shown.books.size
    val title = if (mode == FolderMode.LIBRARY) "$count audiobooks found" else "1 audiobook found"
    com.example.readalong.ui.components.IllustratedDialog(title, model::dismissPreview, actions = {
        TextButton(onClick = model::dismissPreview) { Text("Cancel") }
        Button(onClick = { model.confirmPreview(mode, restore); onAdded() }, enabled = deleted == 0 || restore || !everythingDeleted,
            modifier = Modifier.testTag("confirm_add")) {
            Text(if (mode == FolderMode.LIBRARY) "Add $count books" else "Add book")
        }
    }) {
        Text("In \"${shown.name}\"", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (count >= 2) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(mode == FolderMode.LIBRARY, { mode = FolderMode.LIBRARY }, SegmentedButtonDefaults.itemShape(0, 2), Modifier.testTag("as_library")) { Text("$count separate books") }
            SegmentedButton(mode == FolderMode.ONE_BOOK, { mode = FolderMode.ONE_BOOK }, SegmentedButtonDefaults.itemShape(1, 2), Modifier.testTag("as_one_book")) { Text("One book") }
        }
        if (mode == FolderMode.LIBRARY) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shown.books.take(6).forEach { book ->
                    Text("• ${book.name}" + if (book.deleted) " (deleted earlier)" else "", style = MaterialTheme.typography.bodyLarge,
                        color = if (book.deleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
                if (count > 6) Text("and ${count - 6} more", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("ReadAlong will also check this folder for new books each time you come back.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(if (count >= 2) "All $count folders' audio is played as one audiobook, in order." else "Its audio files are played as one audiobook, in order.",
                style = MaterialTheme.typography.bodyLarge)
        }
        if (deleted > 0) Row(Modifier.fillMaxWidth().toggleable(restore, role = Role.Checkbox) { restore = it }.testTag("restore_deleted"),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(restore, null)
            Spacer(Modifier.width(8.dp))
            Text(if (mode == FolderMode.LIBRARY) "Bring back the $deleted you deleted earlier" else "Bring back this book you deleted earlier",
                style = MaterialTheme.typography.bodyLarge)
        }
    }
}

