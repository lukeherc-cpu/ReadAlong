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
    execution: com.example.readalong.data.processing.PreparationExecution?, onModel: () -> Unit, onQueue: () -> Unit = {}) {
    val sourceBusy by reading.importBusy.collectAsStateWithLifecycle()
    val sourceEpub by reading.importedEdition.collectAsStateWithLifecycle()
    val active = job?.state in listOf("QUEUED", "PROCESSING", "PARTIAL_READY", "GENERATING_EPUB")
    val ready = job?.state == "READY"
    val waitingForCharging = active && job?.message?.startsWith("Waiting for charging") == true
    val progress = job?.progress()
    val created = job != null && job.state != "WAITING_SELECTION"
    val epubPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { it?.let(reading::addEpub) }
    val shownPercent by com.example.readalong.ui.components.animatedPercent(progress?.bookPercent ?: 0)
    val bar = com.example.readalong.ui.components.animatedFraction((progress?.bookPercent ?: 0) / 100f)
    val left = timeLeft(estimate)
    val title = when {
        ready -> "ReadAlong ready"
        !created -> "Make a ReadAlong"
        job?.state == "WAITING_QUEUE" -> "Waiting in queue"
        job?.state == "WAITING_MODEL" -> "Needs the speech model"
        job?.state == "FAILED" -> "ReadAlong stopped"
        active -> "Making ReadAlong"
        else -> "ReadAlong paused"
    }
    // Percentage and time left share the left side of the bar; stopped states say what a tap does.
    val subtitle = when {
        !created -> "Read as you listen"
        ready -> "Whole book · 100%"
        waitingForCharging -> "$shownPercent% · waiting for charging · tap to use battery 30 min"
        job?.state == "WAITING_MODEL" -> "Tap to get it"
        job?.state == "WAITING_QUEUE" -> "$shownPercent% · after the book being made now"
        job?.state == "FAILED" -> "$shownPercent% · tap to try again"
        !active -> "$shownPercent% · tap to continue"
        left != null -> "$shownPercent% · $left left"
        else -> "$shownPercent% of book"
    }
    // Tapping the left side does the one thing that's needed now; making a ReadAlong starts at once
    // (matched to the EPUB if one was added, otherwise from the audio).
    val tap: (() -> Unit)? = when {
        book.isDemo -> null
        !created -> ({ reading.createWithSource(sourceEpub != null, null) {} })
        job?.state == "WAITING_MODEL" -> onModel
        waitingForCharging -> reading::allowBatteryForThirtyMinutes
        job?.state == "WAITING_QUEUE" -> onQueue
        job?.state == "FAILED" || (!active && !ready) -> reading::prepareReadable
        else -> onQueue // making it (or ready): see it in the queue
    }
    val epubSide = when {
        book.isDemo -> null
        sourceEpub.isInitialSyncPending() -> EpubSide("Matching\nEPUB…", busy = true, onClick = null)
        sourceBusy -> EpubSide("Adding\nEPUB…", busy = true, onClick = null)
        sourceEpub != null -> EpubSide("Replace\nmy EPUB") { epubPicker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }
        else -> EpubSide("Add matching\nebook (EPUB)") { epubPicker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }
    }
    // The bar's ^ folds it away to a lone chevron that brings it back (remembered per book).
    val hidden by reading.preparationHidden.collectAsStateWithLifecycle(false)
    androidx.compose.animation.AnimatedVisibility(!hidden, enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(220), expandFrom = Alignment.Top) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)),
        exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(180), shrinkTowards = Alignment.Top) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))) {
        ReadAlongBar(title, subtitle, if (created) ({ bar.value }) else null, onClick = tap, epub = epubSide, onHide = { reading.hidePreparation(true) })
    }
    androidx.compose.animation.AnimatedVisibility(hidden, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { reading.hidePreparation(false) }) {
                Icon(painterResource(R.drawable.ic_down), "Show the ReadAlong bar", Modifier.size(20.dp))
            }
        }
    }
}

/** "About 1 h 48 min remaining" → "1h 48m"; null while there's no estimate yet. */
private fun timeLeft(estimate: String): String? {
    Regex("(\\d+) h (\\d+) min").find(estimate)?.let { return "${it.groupValues[1]}h ${it.groupValues[2]}m" }
    Regex("(\\d+) min").find(estimate)?.let { return "${it.groupValues[1]}m" }
    return null
}

internal data class EpubSide(val label: String, val busy: Boolean = false, val onClick: (() -> Unit)?)

private fun com.example.readalong.data.local.ImportedEpubEntity?.isInitialSyncPending() =
    this != null && alignedRevision < 0 && state in setOf("QUEUED", "ALIGNING")

/** The ReadAlong bar: status, progress and time left on the left; adding or replacing the EPUB on the right. */
@Composable
internal fun ReadAlongBar(title: String, subtitle: String, progress: (() -> Float)?, onClick: (() -> Unit)?, epub: EpubSide?, onHide: (() -> Unit)? = null) {
    val ink = MaterialTheme.colorScheme.onSurface
    Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = Color.White.copy(alpha = .09f), contentColor = ink,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .08f))) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).then(if (onClick != null) Modifier.clickable(onClickLabel = title, onClick = onClick) else Modifier).padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp))
                Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = ink.copy(alpha = .68f))
                if (progress != null) LinearProgressIndicator(progress = progress, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary, trackColor = ink.copy(alpha = .14f), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
            }
            if (epub != null) {
                Box(Modifier.padding(vertical = 14.dp).width(1.dp).fillMaxHeight().background(ink.copy(alpha = .16f)))
                Row(Modifier.fillMaxHeight().then(if (epub.onClick != null) Modifier.clickable(onClickLabel = epub.label.replace("\n", " "), onClick = epub.onClick) else Modifier).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (epub.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ink)
                    Text(epub.label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 15.sp))
                }
            }
            if (onHide != null) {
                Box(Modifier.padding(vertical = 14.dp).width(1.dp).fillMaxHeight().background(ink.copy(alpha = .16f)))
                Box(Modifier.fillMaxHeight().width(36.dp).clickable(onClickLabel = "Hide the ReadAlong bar", onClick = onHide), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_down), null, Modifier.size(18.dp).graphicsLayer { rotationZ = 180f })
                }
            }
        }
    }
}

