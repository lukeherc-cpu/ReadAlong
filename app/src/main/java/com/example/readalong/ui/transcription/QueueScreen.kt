package com.example.readalong.ui.transcription

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.ReadAlongApplication
import com.example.readalong.R
import com.example.readalong.data.local.*
import com.example.readalong.data.processing.*
import com.example.readalong.domain.model.Book
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class QueueBook(val item: PreparationQueueItem, val book: Book?, val job: ProcessingJobEntity?, val chapter: String)
class QueueViewModel(private val app: ReadAlongApplication) : ViewModel() {
    private val repository = app.processing
    internal val books = repository.queue.mapLatest { rows -> rows.map { item ->
        val job = app.database.processingDao().find(item.bookId)
        QueueBook(item, app.bookRepository.observeBook(item.bookId).first(), job, job?.chapterTitle(app.database).orEmpty())
    } }.flowOn(Dispatchers.IO).stateIn<List<QueueBook>?>(viewModelScope, SharingStarted.WhileSubscribed(5000), null) // null: still loading
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun next(id: String) = action { repository.playQueued(id) }
    fun addAll() = action { repository.addAllToQueue() }
    fun pause(id: String) = action { repository.cancel(id) }
    fun remove(id: String) = action { repository.removeFromQueue(id) }
    fun add(id: String) = action { repository.addToQueue(id) }
    /** Saves the new order. A book dragged to the top starts transcribing; the displaced book keeps its checkpoint. */
    fun reorder(ids: List<String>) = action {
        val before = queuedIds()
        repository.reorder(ids)
        startIfPromoted(before.firstOrNull(), ids.firstOrNull())
    }
    fun move(id: String, direction: Int) = action {
        val before = queuedIds()
        repository.move(id, direction)
        if (direction == -1 && before.indexOf(id) == 1) startIfPromoted(before.first(), id)
    }
    private fun queuedIds() = books.value.orEmpty().filter { it.item.state !in setOf("WAITING_SELECTION", "READY") }.map { it.item.bookId }
    private suspend fun startIfPromoted(oldFirst: String?, newFirst: String?) {
        if (newFirst == null || newFirst == oldFirst) return
        val state = books.value?.firstOrNull { it.item.bookId == newFirst }?.item?.state
        if (state !in activeStates) repository.playQueued(newFirst)
    }
    fun dismissError() { mutableError.value = null }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        try { mutableError.value = null; block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutableError.value = failure.message ?: "Couldn't update the queue. Please try again." }
    } }
    companion object {
        fun factory(app: ReadAlongApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = QueueViewModel(app) as T
        }
    }
}

@Composable
fun QueueScreen(model: QueueViewModel, onBack: () -> Unit) {
    val loaded by model.books.collectAsStateWithLifecycle()
    val loading = loaded == null
    val books = loaded.orEmpty()
    val error by model.error.collectAsStateWithLifecycle()
    val queued = books.filter { it.item.state !in setOf("WAITING_SELECTION", "READY") }
    val available = books.filter { it.item.state == "WAITING_SELECTION" }
    val finished = books.filter { it.item.state == "READY" }
    var adding by rememberSaveable { mutableStateOf(false) }
    var order by remember { mutableStateOf(emptyList<String>()) }
    val drag = remember { QueueDrag() }
    val currentQueued by rememberUpdatedState(queued)
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(queued.map { it.item.bookId }) {
        if (drag.key == null || order.toSet() != queued.map { it.item.bookId }.toSet()) {
            order = queued.map { it.item.bookId }; drag.reset()
        }
    }
    // Edge auto-scroll runs every frame while a card is held near the top or bottom, so the list
    // keeps moving under a still finger. The scrolled distance is added back to the card so it
    // stays under the finger instead of drifting with the list.
    LaunchedEffect(drag.key) {
        if (drag.key == null) return@LaunchedEffect
        while (isActive && drag.key != null) {
            withFrameNanos { }
            val speed = drag.edgeSpeed(list)
            if (speed != 0f) {
                val consumed = list.scrollBy(speed)
                drag.moveBy(consumed)
                drag.swapIfNeeded(list, order) { order = it }
            }
        }
    }
    val ordered = order.mapNotNull { id -> queued.firstOrNull { it.item.bookId == id } }
    Scaffold { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).testTag("transcription_queue"), state = list,
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back to library") }
                    Text("Queue", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { adding = true }) { Icon(painterResource(R.drawable.ic_add), "Add book to queue") }
                }
                if (!loading) Text(if (queued.any { it.item.state in activeStates }) "Transcribing" else "Nothing in progress", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Placeholder cards, shaped like the real ones, until the queue has loaded.
            if (loading) items(3, key = { "skeleton:$it" }) { QueueSkeletonCard() }
            else if (ordered.isEmpty()) item(key = "empty") { Text("Tap + to add a book, then tap play on it to start its ReadAlong.", style = MaterialTheme.typography.bodyMedium) }
            items(ordered, key = { it.item.bookId }) { entry ->
                val id = entry.item.bookId
                val held = drag.key == id
                val lift by animateFloatAsState(if (held && !drag.dropping) 1f else 0f, tween(160, easing = QueueEasing), label = "Queue lift")
                QueueCard(entry, (if (held) Modifier else Modifier.animateItem(placementSpec = tween(220, easing = QueueEasing))).zIndex(if (held) 1f else 0f).graphicsLayer {
                        if (held) translationY = drag.offset.value
                        scaleX = 1f + .025f * lift; scaleY = scaleX
                        shadowElevation = 10.dp.toPx() * lift; shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large); clip = false
                    },
                    onPlay = { if (entry.item.state in activeStates) model.pause(id) else model.next(id) }, onTrash = { model.remove(id) },
                    handle = {
                        val color = MaterialTheme.colorScheme.onSurfaceVariant
                        Box(Modifier.size(44.dp).semantics {
                            contentDescription = "Reorder ${entry.item.title}"
                            customActions = listOf(CustomAccessibilityAction("Move up") { model.move(id, -1); true }, CustomAccessibilityAction("Move down") { model.move(id, 1); true })
                        }.pointerInput(id) {
                            detectVerticalDragGestures(
                                onDragStart = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate); scope.launch { drag.start(id) } },
                                onDragEnd = {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureEnd)
                                    scope.launch {
                                        drag.drop()
                                        val ids = order
                                        if (ids.toSet() == currentQueued.map { it.item.bookId }.toSet()) model.reorder(ids)
                                    }
                                },
                                onDragCancel = { scope.launch { drag.drop(); order = currentQueued.map { it.item.bookId } } },
                            ) { change, amount ->
                                change.consume()
                                scope.launch { drag.moveBy(amount); drag.swapIfNeeded(list, order) { order = it } }
                            }
                        }, contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(20.dp)) {
                                val radius = 2.dp.toPx()
                                listOf(.3f, .7f).forEach { x -> listOf(.15f, .5f, .85f).forEach { y -> drawCircle(color, radius, Offset(size.width*x, size.height*y)) } }
                            }
                        }
                    })
            }
            if (finished.isNotEmpty()) {
                item(key = "finished-heading") { Text("Transcribed", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
                items(finished, key = { it.item.bookId }) { entry -> QueueCard(entry, onTrash = { model.remove(entry.item.bookId) }) }
            }
            item(key = "not-transcribed-heading") {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Not transcribed", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(enabled = available.isNotEmpty(), onClick = model::addAll) { Text("Add all") }
                }
            }
            if (available.isEmpty()) item(key = "nothing-available") { Text("Every book has been added to the queue.", style = MaterialTheme.typography.bodyMedium) }
            items(available, key = { it.item.bookId }) { entry ->
                Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.item.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { model.add(entry.item.bookId) }) { Icon(painterResource(R.drawable.ic_add), "Add ${entry.item.title} to queue") }
                    }
                }
            }
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add book to queue") }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp)) {
            if (available.isEmpty()) item { Text("Every book has been added to the queue.") }
            items(available, key = { it.item.bookId }) { entry -> TextButton(onClick = { model.add(entry.item.bookId); adding = false }, modifier = Modifier.fillMaxWidth()) { Text(entry.item.title, Modifier.fillMaxWidth()) } }
        }
    }, confirmButton = { TextButton(onClick = { adding = false }) { Text("Close") } })
    error?.let { message -> AlertDialog(onDismissRequest = model::dismissError, title = { Text("Couldn't update queue") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = model::dismissError) { Text("OK") } }) }
}

private val activeStates = setOf("QUEUED", "PROCESSING", "PARTIAL_READY", "GENERATING_EPUB")
private val QueueEasing = CubicBezierEasing(.2f, 0f, .1f, 1f)

/**
 * Drag state for the queue. A swap happens when the held card's leading edge crosses the middle
 * of its neighbour, which gives natural hysteresis (no flip-flopping between two cards of
 * different heights), and the next swap waits until the list has laid out the previous one, so
 * stale positions can't undo it. That stale undo was the "stuck, move the book above it down" glitch.
 */
@Stable
private class QueueDrag {
    var key by mutableStateOf<String?>(null); private set
    var dropping by mutableStateOf(false); private set
    val offset = Animatable(0f)
    private var awaitingIndex: Int? = null

    suspend fun start(id: String) { offset.snapTo(0f); dropping = false; awaitingIndex = null; key = id }
    suspend fun moveBy(amount: Float) { if (key != null && !dropping) offset.snapTo(offset.value + amount) }
    /** Glides the card into its slot instead of snapping, then releases it. */
    suspend fun drop() {
        if (key == null) return
        dropping = true
        offset.animateTo(0f, tween(200, easing = QueueEasing))
        reset()
    }
    fun reset() { key = null; dropping = false; awaitingIndex = null }

    fun edgeSpeed(list: LazyListState): Float {
        val id = key ?: return 0f
        if (dropping) return 0f
        val info = list.layoutInfo
        val held = info.visibleItemsInfo.firstOrNull { it.key == id } ?: return 0f
        val top = held.offset + offset.value; val bottom = top + held.size
        val edge = info.viewportSize.height * .12f
        return when {
            top < info.viewportStartOffset + edge -> -speed((info.viewportStartOffset + edge - top) / edge)
            bottom > info.viewportEndOffset - edge -> speed((bottom - (info.viewportEndOffset - edge)) / edge)
            else -> 0f
        }
    }
    private fun speed(depth: Float) = 4f + 22f * depth.coerceIn(0f, 1f)

    suspend fun swapIfNeeded(list: LazyListState, order: List<String>, update: (List<String>) -> Unit) {
        val id = key ?: return
        if (dropping) return
        val visible = list.layoutInfo.visibleItemsInfo
        val held = visible.firstOrNull { it.key == id } ?: return
        awaitingIndex?.let { expected -> if (held.index != expected) return else awaitingIndex = null }
        val top = held.offset + offset.value; val bottom = top + held.size
        val up = offset.value < 0
        val target = (if (up) visible.lastOrNull { it.key in order && it.key != id && it.index < held.index && top < it.offset + it.size / 2f }
            else visible.firstOrNull { it.key in order && it.key != id && it.index > held.index && bottom > it.offset + it.size / 2f }) ?: return
        val from = order.indexOf(id); val to = order.indexOf(target.key)
        if (from < 0 || to < 0) return
        // Where the held card will be laid out after the move, so it doesn't jump on screen.
        val newTop = if (up) target.offset else target.offset + target.size - held.size
        // Moving into or out of the first visible slot makes LazyColumn re-anchor and jump; pin it.
        val first = list.firstVisibleItemIndex
        if (held.index == first || target.index == first) list.requestScrollToItem(first, list.firstVisibleItemScrollOffset)
        update(order.toMutableList().apply { add(to, removeAt(from)) })
        awaitingIndex = target.index
        offset.snapTo(top - newTop)
    }
}

/** A queue card's shape in shimmering placeholders: title, status line, progress bar, buttons and Details. */
@Composable
private fun QueueSkeletonCard() {
    val brush = com.example.readalong.ui.components.shimmerBrush()
    Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth(.6f).height(22.dp).clip(RoundedCornerShape(11.dp)).background(brush))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth(.75f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(brush))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(brush))
                }
                repeat(2) { Box(Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(brush)) }
            }
            Box(Modifier.width(64.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
        }
    }
}

@Composable
private fun QueueCard(entry: QueueBook,modifier: Modifier = Modifier, onPlay: (() -> Unit)? = null, onTrash: () -> Unit, handle: (@Composable () -> Unit)? = null) {
    var details by rememberSaveable(entry.item.bookId) { mutableStateOf(false) }
    val progress = entry.job?.progress()
    val palette = entry.book?.let { com.example.readalong.ui.components.rememberCoverPalette(it) }
    val accent = (if (com.example.readalong.ui.theme.LocalAppSettings.current.libraryLight) palette?.preview else palette?.accent) ?: MaterialTheme.colorScheme.primary
    val bar = com.example.readalong.ui.components.animatedFraction((progress?.bookPercent ?: 0) / 100f)
    val bookPercent by com.example.readalong.ui.components.animatedPercent(progress?.bookPercent ?: 0)
    val chapterPercent by com.example.readalong.ui.components.animatedPercent(progress?.chapterPercent ?: 0)
    Surface(modifier.testTag("queue:${entry.item.bookId}"), shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(entry.item.title, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when {
                        entry.item.state == "READY" -> "Whole book · 100%"
                        entry.item.state == "WAITING_MODEL" -> "Needs the offline speech model"
                        entry.item.state == "WAITING_QUEUE" -> "Waiting in queue · $bookPercent% of book"
                        entry.item.message?.startsWith("Waiting for charging") == true -> "Waiting for charging · $bookPercent% of book"
                        entry.job?.planJson == null -> "Ready to transcribe"
                        // Same number as the player's ReadAlong card: the whole book first, the chapter second.
                        else -> "$bookPercent% of book" + (entry.chapter.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { bar.value }, modifier = Modifier.fillMaxWidth().height(4.dp), color = accent, trackColor = accent.copy(alpha = .2f))
                }
                if (onPlay != null) IconButton(onClick = onPlay) { Icon(painterResource(if (entry.item.state in activeStates) R.drawable.ic_pause else R.drawable.ic_play),
                    if (entry.item.state in activeStates) "Pause ${entry.item.title}" else "Transcribe ${entry.item.title}", Modifier.size(28.dp)) }
                IconButton(onClick = onTrash) { Icon(painterResource(R.drawable.ic_trash), "Remove ${entry.item.title} from queue") }
                handle?.invoke()
            }
            TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 32.dp)) {
                Text("Details", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(painterResource(R.drawable.ic_down), if (details) "Hide details" else "Show details", Modifier.size(16.dp).graphicsLayer { rotationZ = if (details) 180f else 0f })
                if (entry.item.state != "READY" && entry.job?.planJson != null) { Spacer(Modifier.width(8.dp)); Text("This chapter $chapterPercent%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (details) Text(entry.item.message.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
