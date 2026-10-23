package com.example.readalong.ui.transcription

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
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
    } }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun next(id: String) = action { repository.playQueued(id) }
    fun addAll() = action { repository.addAllToQueue() }
    fun pause(id: String) = action { repository.cancel(id) }
    fun remove(id: String) = action { repository.removeFromQueue(id) }
    fun add(id: String) = action { repository.addToQueue(id) }
    fun reorder(ids: List<String>) = action { repository.reorder(ids) }
    fun move(id: String, direction: Int) = action { repository.move(id, direction) }
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
    val books by model.books.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val queued = books.filter { it.item.state !in setOf("WAITING_SELECTION", "READY") }
    val available = books.filter { it.item.state == "WAITING_SELECTION" }
    val finished = books.filter { it.item.state == "READY" }
    var adding by rememberSaveable { mutableStateOf(false) }
    var order by remember { mutableStateOf(emptyList<String>()) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val currentQueued by rememberUpdatedState(queued)
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(queued.map { it.item.bookId }) {
        if (dragging == null || order.toSet() != queued.map { it.item.bookId }.toSet()) {
            order = queued.map { it.item.bookId }; dragging = null; dragOffset = 0f
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
                Text("${queued.count { it.item.state in activeStates }} of 1 transcribing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ordered.isEmpty()) item(key = "empty") { Text("Press + to add a book. Play starts transcription.", style = MaterialTheme.typography.bodyMedium) }
            items(ordered, key = { it.item.bookId }) { entry ->
                val id = entry.item.bookId
                QueueCard(entry, (if (dragging == id) Modifier else Modifier.animateItem()).zIndex(if (dragging == id) 1f else 0f).graphicsLayer { if (dragging == id) translationY = dragOffset },
                    onPlay = { if (entry.item.state in activeStates) model.pause(id) else model.next(id) }, onTrash = { model.remove(id) },
                    handle = {
                        val color = MaterialTheme.colorScheme.onSurfaceVariant
                        Box(Modifier.size(44.dp).semantics {
                            contentDescription = "Reorder ${entry.item.title}"
                            customActions = listOf(CustomAccessibilityAction("Move up") { model.move(id, -1); true }, CustomAccessibilityAction("Move down") { model.move(id, 1); true })
                        }.pointerInput(id) {
                            detectDragGestures(onDragStart = { dragging = id; dragOffset = 0f }, onDragCancel = { dragging = null; dragOffset = 0f; order = currentQueued.map { it.item.bookId } }, onDragEnd = {
                                val ids = order
                                dragging = null; dragOffset = 0f
                                if (ids.toSet() == currentQueued.map { it.item.bookId }.toSet()) model.reorder(ids)
                            }) { change, amount ->
                                change.consume(); dragOffset += amount.y
                                val visible = list.layoutInfo.visibleItemsInfo
                                val current = visible.firstOrNull { it.key == id } ?: return@detectDragGestures
                                val center = current.offset + current.size / 2 + dragOffset
                                val target = visible.firstOrNull { it.key in order && it.key != id && center >= it.offset && center < it.offset + it.size }
                                if (target != null) {
                                    val old = order.indexOf(id); val next = order.indexOf(target.key)
                                    if (old >= 0 && next >= 0) {
                                        order = order.toMutableList().apply { add(next, removeAt(old)) }
                                        dragOffset -= target.offset - current.offset
                                    }
                                }
                                val edge = 72.dp.toPx()
                                val scroll = when {
                                    center < list.layoutInfo.viewportStartOffset + edge -> -12.dp.toPx()
                                    center > list.layoutInfo.viewportEndOffset - edge -> 12.dp.toPx()
                                    else -> 0f
                                }
                                if (scroll != 0f) scope.launch { list.scrollBy(scroll) }
                            }
                        }, contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(20.dp)) {
                                val radius = 2.5.dp.toPx()
                                listOf(.25f, .75f).forEach { x -> listOf(.25f, .75f).forEach { y -> drawCircle(color, radius, Offset(size.width*x, size.height*y)) } }
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
            if (available.isEmpty()) item(key = "nothing-available") { Text("Every book has been added to the queue.", style = MaterialTheme.typography.bodySmall) }
            items(available, key = { it.item.bookId }) { entry ->
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
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

@Composable
private fun QueueCard(entry: QueueBook, modifier: Modifier = Modifier, onPlay: (() -> Unit)? = null, onTrash: () -> Unit, handle: (@Composable () -> Unit)? = null) {
    var details by rememberSaveable(entry.item.bookId) { mutableStateOf(false) }
    val progress = entry.job?.progress()
    val palette = entry.book?.let { com.example.readalong.ui.components.rememberCoverPalette(it) }
    val accent = (if (com.example.readalong.ui.theme.LocalAppSettings.current.libraryLight) palette?.preview else palette?.accent) ?: MaterialTheme.colorScheme.primary
    Surface(modifier.testTag("queue:${entry.item.bookId}"), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(entry.item.title, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when {
                        entry.item.state == "READY" -> "Full book · 100%"
                        entry.item.state == "WAITING_MODEL" -> "Waiting for Whistle"
                        entry.item.state == "WAITING_QUEUE" -> "Waiting in queue"
                        entry.item.message?.startsWith("Waiting for charging") == true -> "Waiting for charging"
                        entry.job?.planJson == null -> "Ready to transcribe"
                        else -> "${entry.chapter} · ${progress?.chapterPercent ?: 0}%"
                    }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { (progress?.bookPercent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp), color = accent, trackColor = accent.copy(alpha = .2f))
                }
                if (onPlay != null) IconButton(onClick = onPlay) { Icon(painterResource(if (entry.item.state in activeStates) R.drawable.ic_pause else R.drawable.ic_play),
                    if (entry.item.state in activeStates) "Pause ${entry.item.title}" else "Transcribe ${entry.item.title}", Modifier.size(28.dp)) }
                IconButton(onClick = onTrash) { Icon(painterResource(R.drawable.ic_trash), "Remove ${entry.item.title} from queue") }
                handle?.invoke()
            }
            TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 32.dp)) {
                Text("Details", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(painterResource(R.drawable.ic_down), if (details) "Hide details" else "Show details", Modifier.size(16.dp).graphicsLayer { rotationZ = if (details) 180f else 0f })
                Spacer(Modifier.width(8.dp)); Text("Full book ${progress?.bookPercent ?: 0}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (details) Text(entry.item.message.orEmpty(), style = MaterialTheme.typography.bodySmall)
        }
    }
}
