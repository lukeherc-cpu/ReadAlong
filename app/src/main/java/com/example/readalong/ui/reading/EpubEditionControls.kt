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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!compact) Text("Reading edition · original EPUB by default", style = MaterialTheme.typography.titleSmall)
        TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }) {
            Text(if (busy) "Adding EPUB…" else if (edition == null) "Add my EPUB (optional)" else "Replace my EPUB")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!compact) EpubSyncProgress(model)
        if (compact) edition?.let { Text("EPUB uploaded · ${it.title}", style = MaterialTheme.typography.bodySmall) }
        else edition?.let { epub ->
            Text(epub.title, style = MaterialTheme.typography.bodyMedium)
            Text(epub.message, style = MaterialTheme.typography.bodySmall)
            if (epub.manualCount > 0) Text("${epub.manualCount} manual sync ${if (epub.manualCount == 1) "point" else "points"} · takes priority", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { model.chooseEdition("EPUB", position) }) { Text(if (epub.useForReading()) "● My EPUB · always (default)" else "Use my EPUB everywhere") }
            TextButton(onClick = { model.chooseEdition("GENERATED", position) }) { Text(if (epub.preference == "GENERATED") "● ReadAlong generated" else "Use ReadAlong generated") }
            Text("Your EPUB is shown in every reading view, including unmatched text. Short gaps between timed passages use estimated sentence timing, starting at the preceding passage’s end. Taps use that estimate; other unmatched text uses the nearest earlier timed sentence.", style = MaterialTheme.typography.bodySmall)
            if (epub.state == "FAILED" || epub.state == "UNUSABLE") TextButton(onClick = model::retryAlignment) { Text("Retry EPUB alignment") }
            TextButton(onClick = { confirmRemoval = true }) { Text("Remove my EPUB") }
        }
    }
    if (confirmRemoval) AlertDialog(onDismissRequest = { confirmRemoval = false }, title = { Text("Remove your EPUB?") },
        text = { Text("The original file, generated edition, transcript and audiobook are kept.") },
        confirmButton = { TextButton(onClick = { model.removeEpub(position); confirmRemoval = false }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Cancel") } })
}
