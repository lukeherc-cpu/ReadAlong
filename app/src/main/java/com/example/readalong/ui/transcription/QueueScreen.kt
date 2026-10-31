package com.example.readalong.ui.transcription

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    /** Offered only on a stopped (FAILED) book: makes its ReadAlong again from the start. */
    fun rebuild(id: String) = action { repository.rebuild(id) }
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
fun QueueScreen(model: QueueViewModel, onBack: () -> Unit, onOpen: (String) -> Unit = {}) {
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
    // Only "Up next" rows can be dragged, so the large card for the book being transcribed stays first.
    // Read at call time: the drag handlers outlive the composition that created them.
    val currentId by rememberUpdatedState(order.firstOrNull()?.takeIf { id -> queued.firstOrNull { it.item.bookId == id }?.item?.state in activeStates })
    fun upNextIds() = order.filter { it != currentId }
    fun setUpNext(ids: List<String>) { order = listOfNotNull(currentId) + ids }
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
                drag.swapIfNeeded(list, upNextIds()) { setUpNext(it) }
            }
        }
    }
    val ordered = order.mapNotNull { id -> queued.firstOrNull { it.item.bookId == id } }
    // The book being transcribed gets the large card; the rest of the queue sits under "Up next".
    val current = ordered.firstOrNull()?.takeIf { it.item.state in activeStates }
    val upNext = if (current == null) ordered else ordered.drop(1)
    Scaffold { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).testTag("transcription_queue"), state = list,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "header") {
                Column(Modifier.padding(bottom = 16.dp)) {
                    Row(Modifier.fillMaxWidth().offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back to library") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { adding = true }, Modifier.offset(x = 24.dp)) { Icon(painterResource(R.drawable.ic_add), "Add book to queue") }
                    }
                    Text("Queue", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            }
            // Placeholders, shaped like the real card and rows, until the queue has loaded.
            if (loading) {
                item(key = "skeleton:current") { QueueSkeletonCard() }
                items(2, key = { "skeleton:$it" }) { QueueSkeletonRow() }
            }
            else if (ordered.isEmpty() && finished.isEmpty()) item(key = "empty") { Text("Tap + to add a book, then tap play on it to start its ReadAlong.", style = MaterialTheme.typography.bodyMedium) }
            if (current != null) item(key = current.item.bookId) {
                val id = current.item.bookId
                SwipeToRemove(current.item.title, enabled = drag.key == null, onRemove = { model.remove(id) }, modifier = Modifier.animateItem(placementSpec = tween(220, easing = QueueEasing))) {
                    TranscribingCard(current, onOpen = { onOpen(id) }, onPause = { model.pause(id) })
                }
            }
            if (upNext.isNotEmpty()) item(key = "up-next-heading") { QueueHeading("Up next", Modifier.padding(top = if (current == null) 0.dp else 20.dp)) }
            items(upNext, key = { it.item.bookId }) { entry ->
                val id = entry.item.bookId
                val held = drag.key == id
                val lift by animateFloatAsState(if (held && !drag.dropping) 1f else 0f, tween(160, easing = QueueEasing), label = "Queue lift")
                SwipeToRemove(entry.item.title, enabled = drag.key == null, onRemove = { model.remove(id) },
                    modifier = (if (held) Modifier else Modifier.animateItem(placementSpec = tween(220, easing = QueueEasing))).zIndex(if (held) 1f else 0f).graphicsLayer {
                        if (held) translationY = drag.offset.value
                        scaleX = 1f + .025f * lift; scaleY = scaleX
                        shadowElevation = 10.dp.toPx() * lift; shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium); clip = false
                    }) {
                    // Hold a row to pick it up, then drag it into place.
                    QueueRow(entry, onOpen = { onOpen(id) }, modifier = Modifier.semantics {
                        customActions = listOf(CustomAccessibilityAction("Move up") { model.move(id, -1); true }, CustomAccessibilityAction("Move down") { model.move(id, 1); true })
                    }.pointerInput(id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); scope.launch { drag.start(id) } },
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
                            scope.launch { drag.moveBy(amount.y); drag.swapIfNeeded(list, upNextIds()) { setUpNext(it) } }
                        }
                    }, action = {
                        Row {
                            if (entry.item.state == "FAILED") IconButton(onClick = { model.rebuild(id) }) {
                                Icon(painterResource(R.drawable.ic_redo), "Rebuild ${entry.item.title} from the start", Modifier.size(20.dp))
                            }
                            IconButton(onClick = { model.next(id) }) {
                                Icon(painterResource(R.drawable.ic_play), if (entry.item.state == "FAILED") "Try ${entry.item.title} again" else "Transcribe ${entry.item.title} now", Modifier.size(20.dp))
                            }
                        }
                    })
                }
            }
            if (finished.isNotEmpty()) {
                item(key = "finished-heading") { QueueHeading("Transcribed", Modifier.padding(top = 20.dp)) }
                items(finished, key = { it.item.bookId }) { entry ->
                    SwipeToRemove(entry.item.title, enabled = true, onRemove = { model.remove(entry.item.bookId) }) { QueueRow(entry, onOpen = { onOpen(entry.item.bookId) }) }
                }
            }
        }
    }
    if (adding) AddToQueueSheet(available, onAdd = model::add, onAddAll = { model.addAll(); adding = false }, onDismiss = { adding = false })
    error?.let { message -> AlertDialog(onDismissRequest = model::dismissError, title = { Text("Couldn't update queue") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = model::dismissError) { Text("OK") } }) }
}

/**
 * Books not in the queue yet, in a sheet opened from +. Search narrows the list; an added book
 * slides out of it so several can be added in a row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToQueueSheet(available: List<QueueBook>, onAdd: (String) -> Unit, onAddAll: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = if (query.isBlank()) available else available.filter { entry ->
        entry.item.title.contains(query.trim(), ignoreCase = true) || entry.book?.author?.contains(query.trim(), ignoreCase = true) == true
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 20.dp).testTag("add_to_queue")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Add to queue", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(painterResource(R.drawable.ic_search), "Search books") }
                TextButton(enabled = available.isNotEmpty(), onClick = onAddAll, modifier = Modifier.offset(x = 12.dp)) { Text("Add all", fontWeight = FontWeight.SemiBold) }
            }
            androidx.compose.animation.AnimatedVisibility(searching,
                enter = androidx.compose.animation.expandVertically(tween(220, easing = QueueEasing)) + androidx.compose.animation.fadeIn(tween(220)),
                exit = androidx.compose.animation.shrinkVertically(tween(180, easing = QueueEasing)) + androidx.compose.animation.fadeOut(tween(140))) {
                com.example.readalong.ui.components.ReadAlongSearchField(query, { query = it }, onClose = { searching = false; query = "" },
                    accent = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    placeholder = "Search by title or author", testTag = "add_to_queue_search")
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                if (available.isEmpty()) item(key = "all-added") { Text("Every book has been added to the queue.", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else if (shown.isEmpty()) item(key = "no-match") { Text("No books match \"${query.trim()}\".", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(shown, key = { it.item.bookId }) { entry ->
                    Box(Modifier.animateItem(fadeInSpec = tween(180), placementSpec = tween(260, easing = QueueEasing), fadeOutSpec = tween(160))) {
                        QueueRow(entry, action = {
                            IconButton(onClick = { onAdd(entry.item.bookId) }) { Icon(painterResource(R.drawable.ic_add), "Add ${entry.item.title} to queue") }
                        })
                    }
                }
            }
        }
    }
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

@Composable
private fun QueueHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Swiping a card or row to the left removes the book from the queue, the way the trash button used to. */
@Composable
private fun SwipeToRemove(title: String, enabled: Boolean, onRemove: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * .35f })
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.EndToStart) return@LaunchedEffect
        onRemove()
        // Still here a moment later (the removal failed): slide the row back.
        delay(1500); state.reset()
    }
    SwipeToDismissBox(state = state, modifier = modifier.semantics { customActions = listOf(CustomAccessibilityAction("Remove $title from queue") { onRemove(); true }) },
        enableDismissFromStartToEnd = false, enableDismissFromEndToStart = enabled, backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(com.example.readalong.ui.theme.Corners.large))
                .background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(painterResource(R.drawable.ic_trash), null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }) { content() }
}

/** The square cover, or a plain tile while the book is still loading. */
@Composable
private fun QueueCover(entry: QueueBook, size: androidx.compose.ui.unit.Dp) {
    val book = entry.book
    if (book != null) com.example.readalong.ui.components.BookCover(book, Modifier.size(size), showTitle = false)
    else Box(Modifier.size(size).clip(RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium)).background(MaterialTheme.colorScheme.surfaceVariant))
}

/** What a waiting or finished book is doing, in a few words. */
private fun QueueBook.status(bookPercent: Int) = when {
    item.state == "READY" -> "Transcribed"
    item.state == "WAITING_MODEL" -> "Needs the offline speech model"
    item.message?.startsWith("Waiting for charging") == true -> "Waiting for charging · $bookPercent% done"
    item.state == "WAITING_QUEUE" -> "Waiting · $bookPercent% done"
    item.state == "FAILED" -> "Stopped · $bookPercent% done"
    job?.planJson == null -> "Ready to transcribe"
    else -> "Paused · $bookPercent% done"
}

/** The book being transcribed: cover, title and chapter, then one progress line and the pause button. Tap it to open the book; a faint "Details" under it opens what it's doing now. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TranscribingCard(entry: QueueBook, onOpen: () -> Unit, onPause: () -> Unit) {
    var details by rememberSaveable(entry.item.bookId) { mutableStateOf(false) }
    val progress = entry.job?.progress()
    val palette = entry.book?.let { com.example.readalong.ui.components.rememberCoverPalette(it) }
    val accent = (if (com.example.readalong.ui.theme.LocalAppSettings.current.libraryLight) palette?.preview else palette?.accent) ?: MaterialTheme.colorScheme.primary
    val bar = com.example.readalong.ui.components.animatedFraction((progress?.bookPercent ?: 0) / 100f)
    val bookPercent by com.example.readalong.ui.components.animatedPercent(progress?.bookPercent ?: 0)
    val chapterPercent by com.example.readalong.ui.components.animatedPercent(progress?.chapterPercent ?: 0)
    val started = entry.job?.planJson != null
    val label = when {
        entry.item.message?.startsWith("Waiting for charging") == true -> "Waiting for charging"
        !started -> "Starting"
        else -> "Transcribing"
    }
    Surface(modifier = Modifier.testTag("queue:${entry.item.bookId}").clip(RoundedCornerShape(com.example.readalong.ui.theme.Corners.large))
            .clickable(onClickLabel = "Open ${entry.item.title}", onClick = onOpen),
        shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                QueueCover(entry, 72.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(entry.item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (entry.chapter.isNotBlank()) Text(entry.chapter, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Same numbers as the player's ReadAlong card: the whole book first, the chapter second.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("$bookPercent%", Modifier.alignByBaseline(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (started) "of book · chapter $chapterPercent%" else "of book", Modifier.alignByBaseline(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    LinearProgressIndicator(progress = { bar.value }, modifier = Modifier.fillMaxWidth().height(6.dp), color = accent, trackColor = accent.copy(alpha = .2f), gapSize = 0.dp, drawStopIndicator = {})
                }
                FilledIconButton(onClick = onPause, modifier = Modifier.size(46.dp)) { Icon(painterResource(R.drawable.ic_pause), "Pause ${entry.item.title}", Modifier.size(20.dp)) }
            }
            val message = entry.item.message.orEmpty()
            if (message.isNotBlank()) Column(Modifier.offset(y = (-6).dp)) {
                val turn by androidx.compose.animation.core.animateFloatAsState(if (details) 180f else 0f, androidx.compose.animation.core.spring(dampingRatio = .8f, stiffness = 400f), label = "Details chevron")
                val faint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f)
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = if (details) "Hide details" else "Show details") { details = !details }
                        .semantics { stateDescription = if (details) "Details shown" else "Details hidden" }.testTag("queue_details")
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Details", style = MaterialTheme.typography.labelLarge, color = faint)
                    Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp).graphicsLayer { rotationZ = 90f + turn }, tint = faint)
                }
                androidx.compose.animation.AnimatedVisibility(details,
                    enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = .9f, stiffness = 380f), expandFrom = Alignment.Top) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, 60)),
                    exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing), shrinkTowards = Alignment.Top) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))) {
                    Text(message, Modifier.padding(top = 4.dp, start = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A plain row: square cover, title, one line under it and a single button. Queued books also get a thin progress line. */
@Composable
private fun QueueRow(entry: QueueBook, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    val queued = entry.item.state != "WAITING_SELECTION"
    val percent = entry.job?.progress()?.bookPercent ?: 0
    val bar = com.example.readalong.ui.components.animatedFraction(percent / 100f)
    val subtitle = if (queued) entry.status(percent) else entry.book?.author
    val shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium)
    Row(modifier.fillMaxWidth().testTag("queue:${entry.item.bookId}").heightIn(min = 64.dp).background(MaterialTheme.colorScheme.background, shape)
            .then(if (onOpen == null) Modifier else Modifier.clip(shape).clickable(onClickLabel = "Open ${entry.item.title}", onClick = onOpen)).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        QueueCover(entry, 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (queued && entry.item.state != "READY" && percent > 0) LinearProgressIndicator(progress = { bar.value }, modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .15f), gapSize = 0.dp, drawStopIndicator = {})
        }
        if (action != null) Box(Modifier.offset(x = 6.dp)) { action() }
    }
}

/** The large card's shape in shimmering placeholders: cover, title lines, progress bar and button. */
@Composable
private fun QueueSkeletonCard() {
    val brush = com.example.readalong.ui.components.shimmerBrush()
    Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium)).background(brush))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(.4f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
                    Box(Modifier.fillMaxWidth(.85f).height(18.dp).clip(RoundedCornerShape(9.dp)).background(brush))
                    Box(Modifier.fillMaxWidth(.6f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(brush))
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(120.dp).height(16.dp).clip(RoundedCornerShape(8.dp)).background(brush))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(brush))
                }
                Box(Modifier.size(46.dp).clip(androidx.compose.foundation.shape.CircleShape).background(brush))
            }
        }
    }
}

/** A row's shape in shimmering placeholders. */
@Composable
private fun QueueSkeletonRow() {
    val brush = com.example.readalong.ui.components.shimmerBrush()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium)).background(brush))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(.7f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(brush))
            Box(Modifier.fillMaxWidth(.35f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
        }
    }
}
