package com.example.readalong.ui.reading

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.data.epub.useForReading

@Composable
fun EpubEditionControls(model: ReadingViewModel, position: Long, compact: Boolean = false) {
    val edition by model.importedEdition.collectAsStateWithLifecycle()
    val busy by model.importBusy.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::addEpub) }
    var confirmRemoval by remember { mutableStateOf(false) }
    // Compact (the player's More panel): right-aligned, and only once an EPUB exists; the bar itself offers adding one.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = if (compact) androidx.compose.ui.Alignment.End else androidx.compose.ui.Alignment.Start) {
        if (!compact) Text("Ebook (EPUB)", style = MaterialTheme.typography.titleSmall)
        if (!compact || edition != null) TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }) {
            Text(if (busy) "Adding EPUB…" else if (edition == null) "Add my EPUB" else "Replace my EPUB")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!compact) EpubSyncProgress(model)
        if (compact) edition?.let { Text("EPUB added · ${it.title}", style = MaterialTheme.typography.bodyMedium) }
        else edition?.let { epub ->
            Text(epub.title, style = MaterialTheme.typography.bodyMedium)
            Text(epub.message, style = MaterialTheme.typography.bodyMedium)
            if (epub.manualCount > 0) Text("${epub.manualCount} sync ${if (epub.manualCount == 1) "point" else "points"} you set", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { model.chooseEdition("EPUB", position) }) { Text(if (epub.useForReading()) "● Show my EPUB's text" else "Show my EPUB's text") }
            TextButton(onClick = { model.chooseEdition("GENERATED", position) }) { Text(if (epub.preference == "GENERATED") "● Show the text made from the audio" else "Show the text made from the audio") }
            Text("Parts of the EPUB that don't match the audio exactly use the nearest timing.", style = MaterialTheme.typography.bodyMedium)
            if (epub.state == "FAILED" || epub.state == "UNUSABLE") TextButton(onClick = model::retryAlignment) { Text("Try matching again") }
            TextButton(onClick = { confirmRemoval = true }) { Text("Remove my EPUB") }
        }
    }
    if (confirmRemoval) AlertDialog(onDismissRequest = { confirmRemoval = false }, title = { Text("Remove your EPUB?") },
        text = { Text("Your EPUB file, audiobook and ReadAlong are kept.") },
        confirmButton = { TextButton(onClick = { model.removeEpub(position); confirmRemoval = false }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Cancel") } })
}
