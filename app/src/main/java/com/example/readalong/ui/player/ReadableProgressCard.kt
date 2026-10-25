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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    var sourceChoice by rememberSaveable(book.id) { mutableStateOf(false) }
    val sourceBusy by reading.importBusy.collectAsStateWithLifecycle()
    val sourceError by reading.error.collectAsStateWithLifecycle()
    val sourceEpub by reading.importedEdition.collectAsStateWithLifecycle()
    var trash by remember { mutableStateOf(false) }
    var redo by remember { mutableStateOf(false) }
    val hidden by reading.preparationHidden.collectAsStateWithLifecycle()
    val active = job?.state in listOf("QUEUED", "PROCESSING", "PARTIAL_READY", "GENERATING_EPUB")
    val ready = job?.state == "READY"
    val waitingForCharging = active && job?.message?.startsWith("Waiting for charging") == true
    val progress = job?.progress()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.example.readalong.ReadAlongApplication
    val chapter by produceState("", job?.planJson, job?.completedUnits) {
        value = job?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { it.chapterTitle(app.database) } }.orEmpty()
    }
    val created = job != null && job.state != "WAITING_SELECTION"
    val epubPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { it?.let(reading::addEpub) }
    val title = if (ready) "ReadAlong ready" else if (job?.state == "WAITING_QUEUE") "Waiting in queue" else if (active) "Making ReadAlong" else if (!created) "Make a ReadAlong" else "ReadAlong paused"
    val shownPercent by com.example.readalong.ui.components.animatedPercent(progress?.bookPercent ?: 0)
    val bar = com.example.readalong.ui.components.animatedFraction((progress?.bookPercent ?: 0) / 100f)
    val subtitle = if (waitingForCharging) "Waiting for charging · $shownPercent% of book" else if (ready) "Whole book · 100%"
        else if (created) "$shownPercent% of book" + (if (chapter.isNotBlank()) " · $chapter" else "") else "Read as you listen"
    // One drop-down: open by default (the bar and its actions), and the bar's ^ folds it all away to a
    // lone chevron that opens it again (remembered per book).
    androidx.compose.animation.AnimatedVisibility(!hidden, enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(220), expandFrom = Alignment.Top) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)),
        exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(180), shrinkTowards = Alignment.Top) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ReadAlongBar(title, subtitle, if (created) ({ bar.value }) else null,
                onClick = if (!created && !book.isDemo) ({ sourceChoice = true }) else null,
                addEpub = if (sourceEpub == null && !book.isDemo) ({ epubPicker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }) else null,
                addingEpub = sourceBusy, onCollapse = { reading.hidePreparation(true) })
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (active && !waitingForCharging) Text(estimate, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                if (job?.state in listOf("FAILED", "WAITING_MODEL")) Text(job?.message.orEmpty(), style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (waitingForCharging) MoreAction("Use battery for 30 min", reading::allowBatteryForThirtyMinutes)
                    if (job?.state == "WAITING_MODEL") MoreAction("Get the speech model", onModel)
                    if (active) MoreAction("Pause", reading::cancelPreparation)
                    else if (created && !ready) MoreAction("Resume", reading::prepareReadable)
                    if (created) MoreAction("Redo") { redo = true }
                    if (created) MoreAction("Remove", destructive = true) { trash = true }
                }
                com.example.readalong.ui.reading.EpubEditionControls(reading, position, compact = true)
            }
        }
    }
    androidx.compose.animation.AnimatedVisibility(hidden, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { reading.hidePreparation(false) }) {
                Icon(painterResource(R.drawable.ic_down), "Show ReadAlong", Modifier.size(20.dp))
            }
        }
    }
    if (sourceChoice) com.example.readalong.ui.importing.ReadAlongSourceDialog(sourceBusy, sourceError,
        hasEpub = sourceEpub != null, onDismiss = { sourceChoice = false }, onStart = { align, uri ->
            reading.createWithSource(align, uri) { sourceChoice = false }
        }, durationMs = book.durationMs, book = book)
    if (redo) AlertDialog(onDismissRequest = { redo = false }, title = { Text("Redo this ReadAlong?") },
        text = { Text("ReadAlong will make this book's text again from the start. Your audio, EPUB and listening position are kept.") },
        confirmButton = { TextButton(onClick = { redo = false; reading.rebuildTranscription() }) { Text("Redo") } },
        dismissButton = { TextButton(onClick = { redo = false }) { Text("Cancel") } })
    if (trash) AlertDialog(onDismissRequest = { trash = false }, title = { Text("Remove this ReadAlong?") },
        text = { Text("Are you sure? This deletes all the text made for this book and takes it off the queue. Your audio, EPUB and listening position are kept, and you can make a new ReadAlong any time.") },
        confirmButton = { TextButton(onClick = { trash = false; reading.removeReadAlong() }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { trash = false }) { Text("Cancel") } })
}

/** The ReadAlong bar: status and whole-book progress, with an "add matching ebook" side when there's no EPUB yet. */
@Composable
internal fun ReadAlongBar(title: String, subtitle: String, progress: (() -> Float)?, onClick: (() -> Unit)?, addEpub: (() -> Unit)?, addingEpub: Boolean = false,
    onCollapse: (() -> Unit)? = null) {
    val ink = MaterialTheme.colorScheme.onSurface
    Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = Color.White.copy(alpha = .09f), contentColor = ink,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .08f))) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).then(if (onClick != null) Modifier.clickable(onClickLabel = title, onClick = onClick) else Modifier).padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp))
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = ink.copy(alpha = .68f))
                if (progress != null) LinearProgressIndicator(progress = progress, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary, trackColor = ink.copy(alpha = .14f), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
            }
            if (addEpub != null) {
                Box(Modifier.padding(vertical = 14.dp).width(1.dp).fillMaxHeight().background(ink.copy(alpha = .16f)))
                Row(Modifier.fillMaxHeight().clickable(enabled = !addingEpub, onClickLabel = "Add matching ebook (EPUB)", onClick = addEpub).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (addingEpub) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = ink)
                    Text("Add matching\nebook (EPUB)", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 15.sp))
                }
            }
            if (onCollapse != null) {
                Box(Modifier.padding(vertical = 14.dp).width(1.dp).fillMaxHeight().background(ink.copy(alpha = .16f)))
                Box(Modifier.fillMaxHeight().width(36.dp).clickable(onClickLabel = "Hide the ReadAlong bar", onClick = onCollapse), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_down), null, Modifier.size(18.dp).graphicsLayer { rotationZ = 180f })
                }
            }
        }
    }
}

@Composable
internal fun MoreAction(label: String, onClick: () -> Unit) = MoreAction(label, false, onClick)
@Composable
internal fun MoreAction(label: String, destructive: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = Color.White.copy(alpha = .09f),
        contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .10f))) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge)
    }
}
