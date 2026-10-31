package com.example.readalong.ui.player

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.R
import com.example.readalong.data.local.ProcessingJobEntity
import com.example.readalong.data.processing.progress
import com.example.readalong.domain.model.Book
import com.example.readalong.ui.reading.ReadingViewModel

private val EpubTypes = arrayOf("application/epub+zip", "application/octet-stream")

/**
 * The back of the cover for a book with no ReadAlong yet: says there's no text, with one button
 * that opens the choice of where the text comes from.
 */
@Composable
internal fun NoTextYet(book: Book, reading: ReadingViewModel, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    var choosing by rememberSaveable(book.id) { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme.onSurface
    Surface(modifier.fillMaxSize().testTag("no_text_yet"), shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large),
        color = Color.White.copy(alpha = .07f), contentColor = ink, border = BorderStroke(1.dp, Color.White.copy(alpha = .09f))) {
        Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(.92f to false, .78f to true, .86f to false).forEach { (width, spoken) ->
                    Box(Modifier.fillMaxWidth(width).height(9.dp).background(
                        (if (spoken) MaterialTheme.colorScheme.primary else ink).copy(alpha = .3f), RoundedCornerShape(5.dp)))
                }
            }
            Text("No text for this book yet", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (!book.isDemo) Button(onClick = { choosing = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("make_readalong_cover")) {
                Text("Make a ReadAlong")
            }
            Text("From the matching ebook or the audio", style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = .62f), textAlign = TextAlign.Center)
            TextButton(onClick = onNotNow, modifier = Modifier.testTag("not_right_now")) { Text("Not right now") }
        }
    }
    if (choosing) MakeReadAlongSheet(reading) { choosing = false }
}

/** Where the text comes from: the matching EPUB (exact text) or the audio itself. */
@Composable
private fun MakeReadAlongSheet(reading: ReadingViewModel, onDismiss: () -> Unit) {
    val epub by reading.importedEdition.collectAsStateWithLifecycle()
    val busy by reading.importBusy.collectAsStateWithLifecycle()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) { reading.createWithSource(true, uri) {}; onDismiss() }
    }
    val colors = MaterialTheme.colorScheme
    com.example.readalong.ui.components.IllustratedDialog("Make a ReadAlong", { if (!busy) onDismiss() }, centered = true, actions = {}) {
        Text("Read the words as they're spoken. Pick where the text comes from.", style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        com.example.readalong.ui.importing.SourceButton(colors.primary to colors.onPrimary, R.drawable.ic_book,
            if (epub != null) "Use my matching ebook" else "Add the matching ebook", "Exact text from your EPUB. Best results.", !busy,
            Modifier.heightIn(min = 84.dp).testTag("reading_source_epub"), large = true) {
            if (epub != null) { reading.createWithSource(true, null) {}; onDismiss() } else picker.launch(EpubTypes)
        }
        com.example.readalong.ui.importing.SourceButton(colors.surfaceVariant to colors.onSurfaceVariant, R.drawable.ic_waveform,
            "Make it from the audio", "Transcribed on this phone. Nothing is uploaded.", !busy,
            Modifier.heightIn(min = 72.dp).testTag("reading_source_transcript"), large = true) {
            reading.createWithSource(false, null) {}; onDismiss()
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/**
 * While a ReadAlong is being made (or has stopped), one small pill over the artwork says where it is;
 * tapping it does the one thing needed now. Nothing shows once it's ready or before it's started.
 */
@Composable
internal fun ReadAlongStatusPill(book: Book, job: ProcessingJobEntity?, estimate: String, reading: ReadingViewModel,
    execution: com.example.readalong.data.processing.PreparationExecution?, onModel: () -> Unit, onQueue: () -> Unit, modifier: Modifier = Modifier) {
    val created = job != null && job.state != "WAITING_SELECTION"
    if (book.isDemo || !created || job?.state == "READY") return
    val active = job?.state in listOf("QUEUED", "PROCESSING", "PARTIAL_READY", "GENERATING_EPUB")
    val waitingForCharging = active && job?.message?.startsWith("Waiting for charging") == true
    val percent by com.example.readalong.ui.components.animatedPercent(job?.progress()?.bookPercent ?: 0)
    val left = timeLeft(estimate)
    var stoppedChoices by rememberSaveable(book.id) { mutableStateOf(false) }
    val label = when {
        job?.state == "WAITING_MODEL" -> "ReadAlong needs the speech model · tap to get it"
        waitingForCharging -> "ReadAlong $percent% · waiting for charging"
        job?.state == "WAITING_QUEUE" -> "ReadAlong $percent% · waiting in queue"
        job?.state == "FAILED" -> "ReadAlong stopped at $percent% · tap"
        !active -> "ReadAlong paused at $percent% · tap to continue"
        left != null -> "Making ReadAlong · $percent% · $left left"
        else -> "Making ReadAlong · $percent%"
    }
    val tap: () -> Unit = when {
        job?.state == "WAITING_MODEL" -> onModel
        waitingForCharging -> reading::allowBatteryForThirtyMinutes
        job?.state == "FAILED" -> ({ stoppedChoices = true })
        !active -> reading::prepareReadable
        else -> onQueue
    }
    if (stoppedChoices && job?.state == "FAILED") StoppedReadAlongDialog(job, percent, execution?.diagnostics,
        onRetry = { stoppedChoices = false; reading.prepareReadable() },
        onRebuild = { stoppedChoices = false; reading.rebuildTranscription() },
        onDismiss = { stoppedChoices = false })
    Surface(modifier.testTag("readalong_status"), shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = .62f), contentColor = Color.White) {
        Row(Modifier.clickable(onClickLabel = label, onClick = tap).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active && !waitingForCharging) CircularProgressIndicator(progress = { percent / 100f }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                color = Color.White, trackColor = Color.White.copy(alpha = .25f))
            else Icon(painterResource(R.drawable.ic_highlighted_book), null, Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Why the ReadAlong stopped, and the two ways forward. Shown only for a FAILED book. */
@Composable
private fun StoppedReadAlongDialog(job: ProcessingJobEntity, percent: Int, diagnostics: String?, onRetry: () -> Unit, onRebuild: () -> Unit, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("ReadAlong stopped") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                job.message?.let { Text(it) }
                Text("Try again continues from $percent%. Rebuild makes this book's ReadAlong again from the start; the text you have stays until it's replaced.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (diagnostics != null) TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(diagnostics)); copied = true },
                    contentPadding = PaddingValues(0.dp)) { Text(if (copied) "Error details copied" else "Copy error details") }
            }
        },
        confirmButton = { TextButton(onClick = onRetry) { Text("Try again") } },
        dismissButton = { TextButton(onClick = onRebuild) { Text("Rebuild") } })
}

/** "About 1 h 48 min remaining" → "1h 48m"; null while there's no estimate yet. */
private fun timeLeft(estimate: String): String? {
    Regex("(\\d+) h (\\d+) min").find(estimate)?.let { return "${it.groupValues[1]}h ${it.groupValues[2]}m" }
    Regex("(\\d+) min").find(estimate)?.let { return "${it.groupValues[1]}m" }
    return null
}

/** The EPUB picker for the player's menu: add the matching ebook, or replace it. */
@Composable
internal fun rememberEpubPicker(reading: ReadingViewModel): () -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { it?.let(reading::addEpub) }
    return { launcher.launch(EpubTypes) }
}
