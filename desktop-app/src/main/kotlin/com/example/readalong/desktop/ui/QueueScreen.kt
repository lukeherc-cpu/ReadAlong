package com.example.readalong.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.readalong.desktop.core.LibraryBook
import com.example.readalong.desktop.transcription.JobState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val QueueEasing = CubicBezierEasing(.2f, 0f, .1f, 1f)
/** The list's spacing between rows. */
private val RowGap = 4.dp

/**
 * The ReadAlong queue, laid out like the phone's: the book being transcribed in a large card with its own
 * pause button, the rest under "Up next" (drag to reorder, play to start one now), stopped books with a
 * retry, and finished books under "Transcribed". There is no queue-wide Pause/Resume button: pausing the
 * card's book pauses the queue, and play on any row resumes it with that book.
 */
@Composable
fun QueueScreen(app: AppState) {
    val queue by app.transcription.queue.collectAsState()
    val job by app.transcription.job.collectAsState()
    val paused by app.transcription.paused.collectAsState()
    val books by app.library.books.collectAsState()
    val errors by app.transcription.errors.collectAsState()
    val finished by app.transcription.finished.collectAsState()
    val revisions by app.transcripts.revisions.collectAsState()
    val byId = books.associateBy { it.id }
    // How far along each book's ReadAlong is, and which books can still be added (both read from disk, so off the UI thread).
    val percents by produceState(emptyMap<String, Int>(), queue, errors, revisions, job?.done) {
        value = withContext(Dispatchers.IO) {
            (queue + errors.keys).associateWith { id ->
                app.transcripts.info(id)?.let { if (it.totalChunks > 0) (it.chunks * 100 / it.totalChunks).coerceAtMost(99) else 0 } ?: 0
            }
        }
    }
    val available by produceState(emptyList<LibraryBook>(), books, queue, errors, revisions) {
        value = withContext(Dispatchers.IO) {
            books.filter { it.id !in queue && it.id !in errors && app.transcripts.info(it.id)?.complete != true }.sortedBy { it.title.lowercase() }
        }
    }
    var adding by remember { mutableStateOf(false) }

    val current = job?.takeIf { !paused }?.let { j -> byId[j.bookId]?.let { it to j } }
    // The local order follows the service's, except while a row is held.
    var order by remember { mutableStateOf(queue) }
    var held by remember { mutableStateOf<String?>(null) }
    var heldOffset by remember { mutableStateOf(0f) }
    LaunchedEffect(queue) { if (held == null || order.toSet() != queue.toSet()) { order = queue; held = null; heldOffset = 0f } }
    val currentId by rememberUpdatedState(current?.first?.id)
    // A row's height plus the gap: the distance a dragged row travels to swap with its neighbour.
    var rowPitch by remember { mutableStateOf(0f) }
    val density = LocalDensity.current.density
    val upNext = order.filter { it != current?.first?.id }.mapNotNull { byId[it] }
    val stopped = errors.keys.filter { it !in queue }.mapNotNull { byId[it] }
    val done = finished.filter { it !in queue }.mapNotNull { byId[it] }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Row(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton("ic_back", "Back (Esc)", { app.back() })
            Spacer(Modifier.weight(1f))
            AppIconButton("ic_add", "Add book to queue", { adding = true })
        }
        Text("Queue", Modifier.padding(start = 4.dp, top = 4.dp, bottom = 16.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        val list = rememberLazyListState()
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth().smoothWheelScroll(list), state = list,
            verticalArrangement = Arrangement.spacedBy(RowGap), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (current == null && upNext.isEmpty() && stopped.isEmpty() && done.isEmpty()) item(key = "empty") {
                Text("Click + to add a book, then press play on it to start its ReadAlong.", Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            current?.let { (book, state) ->
                item(key = "current:${book.id}") {
                    TranscribingCard(book, state, Modifier.animateItem(placementSpec = tween(220, easing = QueueEasing)),
                        onOpen = { app.open(Screen.Player(book.id)) }, onPause = { app.transcription.pause(true) },
                        onRemove = { app.transcription.remove(book.id) })
                }
            }
            if (upNext.isNotEmpty()) item(key = "up-next") { QueueHeading("Up next", Modifier.padding(top = if (current == null) 0.dp else 20.dp).animateItem()) }
            itemsIndexed(upNext, key = { _, b -> b.id }) { index, book ->
                val isHeld = held == book.id
                val lift by animateFloatAsState(if (isHeld) 1f else 0f, tween(160, easing = QueueEasing))
                val percent = percents[book.id] ?: 0
                val status = when {
                    job?.bookId == book.id && paused -> "Paused · $percent% done"
                    paused || current == null -> if (percent > 0) "Paused · $percent% done" else "Ready to transcribe"
                    else -> if (percent > 0) "Waiting · $percent% done" else "Waiting"
                }
                QueueRow(book, status, if (percent > 0) percent / 100f else null,
                    modifier = (if (isHeld) Modifier else Modifier.animateItem(placementSpec = tween(220, easing = QueueEasing)))
                        .zIndex(if (isHeld) 1f else 0f)
                        .onSizeChanged { rowPitch = it.height + RowGap.value * density }
                        .graphicsLayer {
                            if (isHeld) translationY = heldOffset
                            scaleX = 1f + .02f * lift; scaleY = scaleX
                            shadowElevation = 10.dp.toPx() * lift; shape = RoundedCornerShape(Corners.medium); clip = false
                        }
                        // Drag a row to reorder; a book dragged to the top is transcribed next.
                        .pointerInput(book.id) {
                            detectDragGestures(
                                onDragStart = { held = book.id; heldOffset = 0f },
                                onDragEnd = {
                                    val ids = listOfNotNull(currentId) + order.filter { it != currentId }
                                    held = null; heldOffset = 0f
                                    if (ids != queue) app.transcription.reorder(ids)
                                },
                                onDragCancel = { held = null; heldOffset = 0f; order = queue },
                            ) { change, amount ->
                                change.consume()
                                heldOffset += amount.y
                                val pitch = rowPitch.takeIf { it > 0f } ?: (68.dp.toPx())
                                val rows = order.filter { it != currentId }
                                val from = rows.indexOf(book.id)
                                val to = when {
                                    heldOffset > pitch / 2 && from < rows.lastIndex -> from + 1
                                    heldOffset < -pitch / 2 && from > 0 -> from - 1
                                    else -> from
                                }
                                if (to != from && from >= 0) {
                                    order = listOfNotNull(currentId) + rows.toMutableList().apply { add(to, removeAt(from)) }
                                    heldOffset -= (to - from) * pitch
                                }
                            }
                        },
                    onOpen = { app.open(Screen.Player(book.id)) },
                    onRemove = { app.transcription.remove(book.id) }) {
                    AppIconButton("ic_play", if (index == 0 && current == null && percent > 0) "Resume ${book.title}" else "Transcribe ${book.title} now",
                        { app.transcription.start(book.id) }, iconSize = 20.dp)
                }
            }
            if (stopped.isNotEmpty()) item(key = "stopped") { QueueHeading("Stopped", Modifier.padding(top = 20.dp).animateItem()) }
            items(stopped, key = { "stopped:${it.id}" }) { book ->
                val percent = percents[book.id] ?: 0
                QueueRow(book, "Stopped · $percent% done", null, Modifier.animateItem(), detail = errors[book.id],
                    onOpen = { app.open(Screen.Player(book.id)) }, onRemove = { app.transcription.dismissError(book.id) }) {
                    Row {
                        AppIconButton("ic_redo", "Rebuild ${book.title} from the start", { app.transcription.discard(book.id); app.transcription.start(book.id) }, iconSize = 20.dp)
                        AppIconButton("ic_play", "Try ${book.title} again", { app.transcription.start(book.id) }, iconSize = 20.dp)
                    }
                }
            }
            if (done.isNotEmpty()) item(key = "finished") { QueueHeading("Transcribed", Modifier.padding(top = 20.dp).animateItem()) }
            items(done, key = { "done:${it.id}" }) { book ->
                QueueRow(book, book.author ?: "Transcribed", null, Modifier.animateItem(), onOpen = { app.open(Screen.Player(book.id)) },
                    onRemove = { app.transcription.clearFinished(book.id) }, removeLabel = "Clear from list")
            }
        }
    }
    if (adding) AddToQueueDialog(app, available) { adding = false }
}

@Composable
private fun QueueHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The book being transcribed: cover, title and author, then one progress line and the pause button. A faint "Details" opens what it's doing now. */
@Composable
private fun TranscribingCard(book: LibraryBook, job: JobState, modifier: Modifier, onOpen: () -> Unit, onPause: () -> Unit, onRemove: () -> Unit) {
    var details by remember(book.id) { mutableStateOf(false) }
    val palette = rememberCoverPalette(book)
    val accent = palette.accent
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val percent = (job.fraction * 100).toInt()
    val label = when {
        job.downloadProgress != null -> "Downloading speech model"
        job.phase == "Starting" -> "Starting"
        else -> "Transcribing"
    }
    Surface(modifier.fillMaxWidth().clip(RoundedCornerShape(Corners.large)).hoverable(interaction).clickable(onClick = onOpen).pointerHoverIcon(PointerIcon.Hand),
        shape = RoundedCornerShape(Corners.large), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BookCover(book, Modifier.size(72.dp), RoundedCornerShape(Corners.medium), size = 160)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    book.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                // The phone removes by swiping; here the trash shows on hover.
                AnimatedVisibility(hovered, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                    AppIconButton("ic_trash", "Stop and remove from queue", onRemove, iconSize = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${job.downloadProgress?.let { (it * 100).toInt() } ?: percent}%", Modifier.alignByBaseline(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (job.downloadProgress != null) "downloaded" else "of book" + (job.etaMs?.let { " · about ${hoursMinutes(it)} left" } ?: ""),
                            Modifier.alignByBaseline(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ProgressLine(job.downloadProgress ?: job.fraction, color = accent, height = 6.dp)
                }
                FilledIconButton(onPause, Modifier.size(46.dp).pointerHoverIcon(PointerIcon.Hand)) { Icon(AppIcons.get("ic_pause"), "Pause ${book.title}", Modifier.size(20.dp)) }
            }
            val message = listOfNotNull(job.phase.takeIf { it != label && it != "Starting" },
                job.device?.let { (if (job.onGpu) "GPU · " else "") + it + (job.speed?.let { s -> " · %.0f× real time".format(s) } ?: "") }).joinToString("\n")
            if (message.isNotBlank()) Column(Modifier.offset(y = (-6).dp)) {
                val turn by animateFloatAsState(if (details) 180f else 0f, spring(dampingRatio = .8f, stiffness = 400f))
                val faint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f)
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { details = !details }.pointerHoverIcon(PointerIcon.Hand).padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Details", style = MaterialTheme.typography.labelLarge, color = faint)
                    Icon(AppIcons.get("ic_chevron_right"), null, Modifier.size(16.dp).graphicsLayer { rotationZ = 90f + turn }, tint = faint)
                }
                AnimatedVisibility(details, enter = expandVertically(spring(dampingRatio = .9f, stiffness = 380f), expandFrom = Alignment.Top) + fadeIn(tween(220, 60)),
                    exit = shrinkVertically(tween(220, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(120))) {
                    Text(message, Modifier.padding(top = 4.dp, start = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A plain row: square cover, title, one line under it, an optional thin progress line, and its buttons. The trash shows on hover. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(book: LibraryBook, subtitle: String, progress: Float?, modifier: Modifier = Modifier, detail: String? = null,
    onOpen: () -> Unit, onRemove: (() -> Unit)? = null, removeLabel: String = "Remove from queue", action: (@Composable () -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Corners.medium)
    val background by androidx.compose.animation.animateColorAsState(
        if (hovered) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f) else MaterialTheme.colorScheme.background, tween(120))
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape).background(background, shape).hoverable(interaction)
            .clickable(onClick = onOpen).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        BookCover(book, Modifier.size(48.dp), RoundedCornerShape(Corners.small), size = 120)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(book.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = @Composable { Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (detail.isNullOrBlank()) line() else TooltipArea(tooltip = { Tooltip(detail) }, delayMillis = 300) { line() }
            if (progress != null) ProgressLine(progress, Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, height = 3.dp)
        }
        if (onRemove != null) Box(Modifier.size(40.dp)) {
            androidx.compose.animation.AnimatedVisibility(hovered, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                AppIconButton("ic_trash", removeLabel, onRemove, iconSize = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action?.invoke()
    }
}

/** The phone's "Add to queue" sheet: the books not yet transcribed, searchable, with Add all. Added books slide out of the list. */
@Composable
private fun AddToQueueDialog(app: AppState, available: List<LibraryBook>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val shown = if (query.isBlank()) available else available.filter { it.title.contains(query.trim(), true) || it.author?.contains(query.trim(), true) == true }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(560.dp).heightIn(max = 640.dp), shape = RoundedCornerShape(Corners.large), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 12.dp) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Add to queue", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    AppIconButton("ic_search", "Search books", { searching = !searching; if (!searching) query = "" })
                    TextButton({ available.forEach { app.transcription.enqueue(it.id) }; onDismiss() }, enabled = available.isNotEmpty()) { Text("Add all", fontWeight = FontWeight.SemiBold) }
                }
                AnimatedVisibility(searching, enter = expandVertically(tween(220, easing = QueueEasing)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(180, easing = QueueEasing)) + fadeOut(tween(140))) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = { Text("Search by title or author") }, singleLine = true,
                        leadingIcon = { Icon(AppIcons.get("ic_search"), null, Modifier.size(18.dp)) }, shape = CircleShape)
                }
                Spacer(Modifier.height(8.dp))
                val list = rememberLazyListState()
                LazyColumn(Modifier.weight(1f, fill = false).smoothWheelScroll(list), state = list) {
                    if (available.isEmpty()) item { Text("Every book has been added to the queue.", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else if (shown.isEmpty()) item { Text("No books match \"${query.trim()}\".", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(shown, key = { it.id }) { book ->
                        Row(Modifier.fillMaxWidth().animateItem(fadeInSpec = tween(180), placementSpec = tween(260, easing = QueueEasing), fadeOutSpec = tween(160)).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            BookCover(book, Modifier.size(48.dp), RoundedCornerShape(Corners.small), size = 120)
                            Column(Modifier.weight(1f)) {
                                Text(book.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                book.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            AppIconButton("ic_add", "Add ${book.title} to queue", { app.transcription.enqueue(book.id) })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) { TextButton(onDismiss) { Text("Done") } }
            }
        }
    }
}
