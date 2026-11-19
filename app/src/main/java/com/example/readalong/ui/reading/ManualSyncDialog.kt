package com.example.readalong.ui.reading

import com.example.readalong.domain.i18n.tr
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.readalong.data.epub.ManualSyncDraft
import com.example.readalong.domain.reading.parseSyncTime
import com.example.readalong.domain.reading.syncTime

@Composable
fun ManualSyncDialog(draft: ManualSyncDraft, audioMs: Long, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: (Long, Long, Boolean) -> Unit) {
    var start by rememberSaveable(draft.editionId, draft.ordinal) { mutableStateOf(syncTime(draft.startMs)) }
    var end by rememberSaveable(draft.editionId, draft.ordinal) { mutableStateOf(syncTime(draft.endMs)) }
    val from = parseSyncTime(start)
    val until = parseSyncTime(end)
    val valid = from != null && until != null && until > from && until <= draft.durationMs
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(tr("Set sync point")) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(draft.text, maxLines = 4, style = MaterialTheme.typography.bodyMedium)
            Text(tr("Audio is paused. Link this paragraph to the interval below. Check the suggested end time; this changes only this passage's sync, not the rest of the book."), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(start, { start = it.take(20) }, label = { Text(tr("Audio start")) }, singleLine = true, enabled = !busy,
                supportingText = { Text(tr("Hours:minutes:seconds · milliseconds optional")) })
            TextButton(enabled = !busy, onClick = { start = syncTime(audioMs) }) { Text(tr("Use current audio position")) }
            OutlinedTextField(end, { end = it.take(20) }, label = { Text(tr("Audio end")) }, singleLine = true, enabled = !busy)
            if (!valid) Text(tr("Enter valid times with end after start, within ${syncTime(draft.durationMs)}."), color = MaterialTheme.colorScheme.error)
            Text(tr("Manual sync takes priority over automatic matches. Hold this paragraph again to reset it."), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (draft.existing) TextButton(enabled = !busy, onClick = { onSave(draft.startMs, draft.endMs, true) }) { Text(tr("Reset to automatic sync")) }
        }
    }, confirmButton = { TextButton(enabled = valid && !busy, onClick = { onSave(from!!, until!!, false) }) { Text(tr("Save sync")) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("Cancel")) } })
}
