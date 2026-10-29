package com.example.readalong.desktop.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.readalong.desktop.core.LibraryBook
import com.example.readalong.desktop.transcription.JobState
import java.io.File

/** What the reader shows before there is text: make a ReadAlong, or how far along it is. */
@Composable
fun ReadAlongCard(app: AppState, book: LibraryBook, modifier: Modifier = Modifier, ink: Color = Color.White) {
    val job by app.transcription.job.collectAsState()
    val queue by app.transcription.queue.collectAsState()
    val errors by app.transcription.errors.collectAsState()
    val devices by app.transcription.devices.collectAsState()
    val mine = job?.takeIf { it.bookId == book.id }
    Column(modifier.padding(28.dp), horizontalAlignment = Alignment.Start) {
        AnimatedContent(when { mine != null -> "making"; book.id in queue -> "queued"; else -> "start" }, transitionSpec = { fadeIn() togetherWith fadeOut() }) { state ->
            Column {
                when (state) {
                    "making" -> MakingProgress(mine ?: return@Column, ink)
                    "queued" -> {
                        Text("Waiting in the queue", style = MaterialTheme.typography.titleLarge, color = ink)
                        Spacer(Modifier.height(8.dp))
                        Text("This book's ReadAlong starts after ${(queue.indexOf(book.id)).coerceAtLeast(1)} other book(s).", color = ink.copy(alpha = .7f))
                        Spacer(Modifier.height(16.dp))
                        PillButton("Start now", { app.transcription.enqueue(book.id, now = true) }, filled = false)
                    }
                    else -> {
                        Text("Read along with this book", style = MaterialTheme.typography.titleLarge, color = ink)
                        Spacer(Modifier.height(8.dp))
                        val model = app.transcription.model()
                        val gpu = devices?.firstOrNull { it.isGpu }
                        Text("ReadAlong turns the narration into text that follows the voice, on this computer. " +
                            (if (gpu != null) "It'll use your ${gpu.description.ifBlank { gpu.name }} to go faster." else "It runs on the processor; no graphics card was found.") +
                            (if (book.epubPath != null) " The text then comes from your EPUB, ${File(book.epubPath).name}." else ""),
                            color = ink.copy(alpha = .72f), style = MaterialTheme.typography.bodyMedium)
                        errors[book.id]?.let { Spacer(Modifier.height(10.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton("Make ReadAlong", { app.transcription.enqueue(book.id, now = job == null) }, icon = "ic_readalong")
                            PillButton(if (book.epubPath == null) "Add EPUB…" else "Change EPUB…", {
                                chooseFile("Choose the book's EPUB", "epub")?.let { f -> app.library.update(book.id) { it.copy(epubPath = f.path, textSource = "EPUB") } }
                            }, filled = false, icon = "ic_book_add")
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Speech model: ${model.name}" + if (!app.models.isInstalled(model)) " (downloads ${model.sizeBytes / 1_000_000} MB first)" else "",
                            style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = .55f))
                    }
                }
            }
        }
    }
}

@Composable
fun MakingProgress(job: JobState, ink: Color, compact: Boolean = false) {
    Column {
        Text(job.downloadProgress?.let { "${job.phase} · ${(it * 100).toInt()}%" } ?: job.phase,
            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge, color = ink)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(job.fraction * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium, color = ink)
            Spacer(Modifier.width(8.dp))
            Text("of book" + (job.etaMs?.let { " · about ${hoursMinutes(it)} left" } ?: ""), color = ink.copy(alpha = .7f),
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        ProgressLine(job.downloadProgress ?: job.fraction, color = ink)
        Spacer(Modifier.height(10.dp))
        job.device?.let { device ->
            Text((if (job.onGpu) "GPU · " else "") + device + (job.speed?.let { " · %.0f× real time".format(it) } ?: ""),
                style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = .6f))
        }
    }
}
