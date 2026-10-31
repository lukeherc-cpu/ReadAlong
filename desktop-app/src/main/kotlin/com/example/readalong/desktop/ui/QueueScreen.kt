package com.example.readalong.desktop.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The ReadAlong queue: the book being transcribed, on which device and how fast, and what's next. */
@Composable
fun QueueScreen(app: AppState) {
    val queue by app.transcription.queue.collectAsState()
    val job by app.transcription.job.collectAsState()
    val paused by app.transcription.paused.collectAsState()
    val books by app.library.books.collectAsState()
    val errors by app.transcription.errors.collectAsState()
    val revisions by app.transcripts.revisions.collectAsState()
    val byId = books.associateBy { it.id }
    // Books with no finished ReadAlong yet (reading each book's transcript info touches the disk, so off the UI thread).
    val untranscribed by produceState(emptyList<com.example.readalong.desktop.core.LibraryBook>(), books, queue, errors, revisions) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            books.filter { it.id !in queue && it.id !in errors && app.transcripts.info(it.id)?.complete != true }.sortedBy { it.title.lowercase() }
        }
    }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Row(Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton("ic_back", "Back (Esc)", { app.back() })
            Spacer(Modifier.width(10.dp))
            Text("Queue", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (queue.isNotEmpty()) PillButton(if (paused) "Resume" else "Pause", { app.transcription.pause(!paused) }, filled = paused)
            Spacer(Modifier.width(8.dp))
            AppIconButton("ic_add", "Add book to queue", { adding = true }, tint = MaterialTheme.colorScheme.primary)
        }
        val list = rememberLazyListState()
        LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxWidth().smoothWheelScroll(list), state = list, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            val currentId = job?.bookId
            val current = currentId?.let { byId[it] }
            if (current != null && job != null) item(key = "current") {
                val palette = rememberCoverPalette(current)
                Surface(Modifier.fillMaxWidth().animateItem(), shape = RoundedCornerShape(Corners.large), color = lerp(palette.preview, Color(0xFF15191C), .35f)) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        BookCover(current, Modifier.size(112.dp), RoundedCornerShape(Corners.medium), size = 240)
                        Spacer(Modifier.width(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Transcribing", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = .7f))
                            Text(current.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(12.dp))
                            MakingProgress(job!!, Color.White, compact = true)
                            QueueDetails(app, job!!, current)
                        }
                        Spacer(Modifier.width(12.dp))
                        AppIconButton("ic_trash", "Stop and remove from queue", { app.transcription.remove(current.id) }, tint = Color.White)
                    }
                }
            }
            val waiting = queue.filter { it != currentId }
            if (waiting.isNotEmpty()) item(key = "up-next") { SectionTitle("Up next", Modifier.animateItem()) }
            itemsIndexed(waiting, key = { _, id -> id }) { index, id ->
                val book = byId[id] ?: return@itemsIndexed
                ListItem({ Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, Modifier.animateItem().clickable { app.open(Screen.Player(id)) }.pointerHoverIcon(PointerIcon.Hand),
                    supportingContent = { Text(listOfNotNull(book.author, hoursMinutes(book.durationMs)).joinToString(" · ")) },
                    leadingContent = { BookCover(book, Modifier.size(52.dp), RoundedCornerShape(Corners.small), size = 120) },
                    trailingContent = {
                        Row {
                            AppIconButton("ic_play", "Start now", { app.transcription.enqueue(id, now = true) })
                            if (index > 0) AppIconButton("ic_back", "Move up", { app.transcription.move(id, -1) }, Modifier.rotate(90f))
                            AppIconButton("ic_trash", "Remove from queue", { app.transcription.remove(id) })
                        }
                    }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
            }
            val failed = errors.keys.filter { it !in queue }.mapNotNull { byId[it] }
            if (failed.isNotEmpty()) item(key = "failed") { SectionTitle("Stopped") }
            itemsIndexed(failed, key = { _, b -> "failed:${b.id}" }) { _, book ->
                ListItem({ Text(book.title) }, supportingContent = { Text(errors[book.id] ?: "", color = MaterialTheme.colorScheme.error) },
                    trailingContent = { PillButton("Try again", { app.transcription.enqueue(book.id) }, filled = false) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
            }
            if (queue.isEmpty() && failed.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(AppIcons.get("ic_waveform"), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(14.dp))
                    Text("Nothing in the queue", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text("Press + to add a book, or start one below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // As on the phone, the books still without a ReadAlong, ready to add or start.
            if (untranscribed.isNotEmpty()) item(key = "untranscribed") {
                SectionTitle("Not yet transcribed", Modifier.animateItem()) {
                    Text(untranscribed.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            itemsIndexed(untranscribed, key = { _, b -> "todo:${b.id}" }) { _, book ->
                UntranscribedRow(app, book, Modifier.animateItem())
            }
        }
    }
    if (adding) AddToQueueDialog(app, untranscribed) { adding = false }
}

/** A faint "Details" under the book being transcribed; clicking it opens what the transcriber is doing. */
@Composable
private fun QueueDetails(app: AppState, job: com.example.readalong.desktop.transcription.JobState, book: com.example.readalong.desktop.core.LibraryBook) {
    var open by remember(book.id) { mutableStateOf(false) }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, androidx.compose.animation.core.spring(dampingRatio = .8f, stiffness = 400f))
    val faint = Color.White.copy(alpha = .6f)
    Column {
        Row(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = if (open) "Hide details" else "Show details") { open = !open }
                .pointerHoverIcon(PointerIcon.Hand).padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Details", style = MaterialTheme.typography.labelLarge, color = faint)
            Icon(AppIcons.get("ic_chevron_right"), null, Modifier.size(16.dp).rotate(90f + turn), tint = faint)
        }
        AnimatedVisibility(open,
            enter = expandVertically(androidx.compose.animation.core.spring(dampingRatio = .9f, stiffness = 380f), expandFrom = Alignment.Top) + fadeIn(androidx.compose.animation.core.tween(220, 60)),
            exit = shrinkVertically(androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(androidx.compose.animation.core.tween(120))) {
            val model = remember { runCatching { app.transcription.model().name }.getOrNull() }
            val lines = listOfNotNull(
                "Step: ${job.phase}",
                if (job.total > 0) "Pieces done: ${job.done} of ${job.total}" else null,
                job.device?.let { "Running on: " + (if (job.onGpu) "GPU · " else "") + it },
                job.speed?.let { "Speed: %.1f× real time".format(it) },
                model?.let { "Speech model: $it" },
                "Book length: ${hoursMinutes(book.durationMs)}",
            )
            Column(Modifier.padding(top = 4.dp, start = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .75f)) }
            }
        }
    }
}

/** A book without a ReadAlong: add it to the end of the queue, or transcribe it now. */
@Composable
private fun UntranscribedRow(app: AppState, book: com.example.readalong.desktop.core.LibraryBook, modifier: Modifier = Modifier) {
    val partial = remember(book.id) { app.transcripts.info(book.id)?.let { if (it.totalChunks > 0) (it.chunks * 100 / it.totalChunks).coerceAtMost(99) else 0 } ?: 0 }
    ListItem({ Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier.clickable { app.open(Screen.Player(book.id)) }.pointerHoverIcon(PointerIcon.Hand),
        supportingContent = { Text(listOfNotNull(book.author, hoursMinutes(book.durationMs), if (partial > 0) "$partial% done" else null).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { BookCover(book, Modifier.size(52.dp), RoundedCornerShape(Corners.small), size = 120) },
        trailingContent = {
            Row {
                AppIconButton("ic_play", "Transcribe ${book.title} now", { app.transcription.enqueue(book.id, now = true) })
                AppIconButton("ic_add", "Add ${book.title} to queue", { app.transcription.enqueue(book.id) })
            }
        }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
}

/** The phone's "Add to queue" sheet: the books not yet transcribed, searchable, with Add all. Added books slide out of the list. */
@Composable
private fun AddToQueueDialog(app: AppState, available: List<com.example.readalong.desktop.core.LibraryBook>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) available else available.filter { it.title.contains(query.trim(), true) || it.author?.contains(query.trim(), true) == true }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(560.dp).heightIn(max = 640.dp), shape = RoundedCornerShape(Corners.large), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 12.dp) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Add to queue", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton({ available.forEach { app.transcription.enqueue(it.id) }; onDismiss() }, enabled = available.isNotEmpty()) { Text("Add all") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search by title or author") }, singleLine = true,
                    leadingIcon = { Icon(AppIcons.get("ic_search"), null, Modifier.size(18.dp)) }, shape = CircleShape)
                Spacer(Modifier.height(8.dp))
                val list = rememberLazyListState()
                LazyColumn(Modifier.weight(1f, fill = false).smoothWheelScroll(list), state = list) {
                    if (available.isEmpty()) item { Text("Every book has a ReadAlong or is already in the queue.", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else if (shown.isEmpty()) item { Text("No books match \"${query.trim()}\".", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    itemsIndexed(shown, key = { _, b -> b.id }) { _, book ->
                        ListItem({ Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, Modifier.animateItem(),
                            supportingContent = { book.author?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                            leadingContent = { BookCover(book, Modifier.size(48.dp), RoundedCornerShape(Corners.small), size = 120) },
                            trailingContent = { AppIconButton("ic_add", "Add ${book.title} to queue", { app.transcription.enqueue(book.id) }, tint = MaterialTheme.colorScheme.primary) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) { TextButton(onDismiss) { Text("Done") } }
            }
        }
    }
}
