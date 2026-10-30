package com.example.readalong.desktop.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val byId = books.associateBy { it.id }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton("ic_back", "Back (Esc)", { app.back() })
            Spacer(Modifier.width(10.dp))
            Text("Queue", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (queue.isNotEmpty()) PillButton(if (paused) "Resume" else "Pause", { app.transcription.pause(!paused) }, filled = paused)
        }
        LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
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
                Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(AppIcons.get("ic_waveform"), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(14.dp))
                    Text("Nothing in the queue", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text("Open a book and choose Make ReadAlong, or right-click a cover in the library.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
