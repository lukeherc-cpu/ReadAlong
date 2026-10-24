package com.example.readalong.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.example.readalong.data.processing.chapterTitle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.readalong.R
import com.example.readalong.domain.model.Book
import com.example.readalong.data.local.ProcessingJobEntity
import com.example.readalong.data.processing.progress
import com.example.readalong.ui.reading.ReadingViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun ReadableProgressCard(book: Book, job: ProcessingJobEntity?, estimate: String, reading: ReadingViewModel, position: Long,
    execution: com.example.readalong.data.processing.PreparationExecution?, onModel: () -> Unit) {
    var more by rememberSaveable(book.id) { mutableStateOf(false) }
    var sourceChoice by rememberSaveable(book.id) { mutableStateOf(false) }
    val sourceBusy by reading.importBusy.collectAsStateWithLifecycle()
    val sourceError by reading.error.collectAsStateWithLifecycle()
    val sourceEpub by reading.importedEdition.collectAsStateWithLifecycle()
    var trash by remember { mutableStateOf(false) }
    var redo by remember { mutableStateOf(false) }
    val hidden by reading.preparationHidden.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val active = job?.state in listOf("QUEUED", "PROCESSING", "PARTIAL_READY", "GENERATING_EPUB")
    val ready = job?.state == "READY"
    val waitingForCharging = active && job?.message?.startsWith("Waiting for charging") == true
    val progress = job?.progress()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.example.readalong.ReadAlongApplication
    val chapter by produceState("", job?.planJson, job?.completedUnits) {
        value = job?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { it.chapterTitle(app.database) } }.orEmpty()
    }
    val created = job != null && job.state != "WAITING_SELECTION"
    val title = if (ready) "ReadAlong ready" else if (job?.state == "WAITING_QUEUE") "Waiting in queue" else if (active) "Making ReadAlong" else if (!created) "Make a ReadAlong" else "ReadAlong paused"
    if (!hidden || more) Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f)) {
        Column(Modifier.fillMaxWidth()) {
            // Match the home mini-player: 52 dp contents plus 8 dp padding on each side.
            Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val shownPercent by com.example.readalong.ui.components.animatedPercent(progress?.bookPercent ?: 0)
                    val bar = com.example.readalong.ui.components.animatedFraction((progress?.bookPercent ?: 0) / 100f)
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    // The whole-book percentage everywhere (here and in the queue); the chapter is secondary.
                    Text(if (waitingForCharging) "Waiting for charging · $shownPercent% of book" else if (ready) "Whole book · 100%" else if (created) "$shownPercent% of book" + (if (chapter.isNotBlank()) " · $chapter" else "") else "Read the words as you listen",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (created) LinearProgressIndicator(progress = { bar.value }, modifier = Modifier.fillMaxWidth().height(2.dp))
                }
                IconButton(modifier = Modifier.size(40.dp), onClick = {
                    if (active) reading.cancelPreparation() else if (ready) { reading.hidePreparation(true); more = false } else if (!created && !book.isDemo) sourceChoice = true else reading.prepareReadable()
                }) {
                    Icon(painterResource(if (active) R.drawable.ic_pause else if (ready) R.drawable.ic_down else R.drawable.ic_play),
                        if (active) "Pause ReadAlong" else if (ready) "Hide ReadAlong card" else if (created) "Resume ReadAlong" else "Make a ReadAlong", Modifier.size(22.dp))
                }
                IconButton(onClick = { reading.hidePreparation(true); more = false }, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.ic_hide), "Hide ReadAlong card", Modifier.size(20.dp))
                }
                if (created) IconButton(onClick = { redo = true }, modifier = Modifier.size(36.dp)) { Icon(painterResource(R.drawable.ic_redo), "Redo ReadAlong", Modifier.size(21.dp)) }
                if (created) IconButton(onClick = { trash = true }, modifier = Modifier.size(36.dp)) { Icon(painterResource(R.drawable.ic_trash), "Remove ReadAlong", Modifier.size(21.dp)) }
            }

        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { more = !more }) {
            Text("More", style = MaterialTheme.typography.labelMedium)
            Icon(painterResource(R.drawable.ic_down), null, Modifier.size(16.dp))
        }
    }
    if (more && hidden) TextButton(onClick = { reading.hidePreparation(false) }) { Text("Show ReadAlong card on player") }
    if (more) Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (waitingForCharging) TextButton(onClick = reading::allowBatteryForThirtyMinutes) { Text("Use battery for 30 min", style = MaterialTheme.typography.labelMedium) }
                if (active && !waitingForCharging) Text(estimate, style = MaterialTheme.typography.bodyMedium)
                if (job?.state in listOf("FAILED", "WAITING_MODEL")) Text(job?.message.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                if (job?.state == "WAITING_MODEL") TextButton(onClick = onModel) { Text("Get the offline speech model") }
                TextButton(onClick = { execution?.let { scope.launch { clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("ReadAlong diagnostics", it.diagnostics))) } } }, enabled = execution != null) { Text("Copy diagnostics for support") }
                com.example.readalong.ui.reading.EpubEditionControls(reading, position, compact = true)
            }
    if (sourceChoice) com.example.readalong.ui.importing.ReadAlongSourceDialog(sourceBusy, sourceError,
        hasEpub = sourceEpub != null, onDismiss = { sourceChoice = false }, onStart = { align, uri ->
            reading.createWithSource(align, uri) { sourceChoice = false }
        }, durationMs = book.durationMs)
    if (redo) AlertDialog(onDismissRequest = { redo = false }, title = { Text("Redo this ReadAlong?") },
        text = { Text("ReadAlong will make this book's text again from the start. Your audio, EPUB and listening position are kept.") },
        confirmButton = { TextButton(onClick = { redo = false; reading.rebuildTranscription() }) { Text("Redo") } },
        dismissButton = { TextButton(onClick = { redo = false }) { Text("Cancel") } })
    if (trash) AlertDialog(onDismissRequest = { trash = false }, title = { Text("Remove this ReadAlong?") },
        text = { Text("Are you sure? This deletes all the text made for this book and takes it off the queue. Your audio, EPUB and listening position are kept, and you can make a new ReadAlong any time.") },
        confirmButton = { TextButton(onClick = { trash = false; reading.removeReadAlong() }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { trash = false }) { Text("Cancel") } })
}
