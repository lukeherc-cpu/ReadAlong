package com.example.readalong.ui.importing

import com.example.readalong.domain.i18n.tr
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.data.importing.FolderMode

@Composable
fun ImportControls(model: ImportViewModel, onFolders: () -> Unit, onBook: (String) -> Unit = {}) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    val addedBook by model.addedBook.collectAsStateWithLifecycle()
    val priority by model.priority.collectAsStateWithLifecycle()
    val epubUploaded by model.epubUploaded.collectAsStateWithLifecycle()
    val add = rememberAddAudiobooks(model, "add_button")
    val epub = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::addEpub) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.files(listOf(uri)) } }
    var audible by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { choosing = true }, enabled = !busy) {
        Icon(androidx.compose.ui.res.painterResource(com.example.readalong.R.drawable.ic_add), tr("Add audiobook"))
    }

    // Closed by tapping outside or swiping down: no Cancel button.
    if (choosing) com.example.readalong.ui.components.IllustratedDialog("Add audiobook", { choosing = false }, centered = true, actions = {}) {
        // The same shades as the welcome page's choices, darkest first.
        val shades = sourceShades()
        SourceButton(shades[0], com.example.readalong.R.drawable.ic_bookshelf, "Add one book", null, true,
            Modifier.heightIn(min = 96.dp).testTag("add_one_book"), large = true, hero = true) { choosing = false; add() }
        SourceButton(shades[1], com.example.readalong.R.drawable.ic_play, "Add audio file", null, true, Modifier.heightIn(min = 64.dp), large = true) {
            choosing = false; files.launch(arrayOf("audio/*", "application/octet-stream", "video/mp4"))
        }
        SourceButton(shades[2], com.example.readalong.R.drawable.ic_headphones, "Import from Audible", null, true, Modifier.heightIn(min = 64.dp), large = true) {
            choosing = false; audible = true
        }
        TextButton(onClick = { choosing = false; onFolders() }, modifier = Modifier.heightIn(min = 44.dp)) {
            Icon(androidx.compose.ui.res.painterResource(com.example.readalong.R.drawable.ic_bookshelf), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("Manage library folders"))
        }
    }
    if (audible) com.example.readalong.ui.audible.AudibleImportEntry(onChooseFolder = { audible = false; add() }, onDismiss = { audible = false })
}

@Composable
fun ImportStatus(model: ImportViewModel) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val reading by model.readingFolder.collectAsStateWithLifecycle()
    val progress by model.scanProgress.collectAsStateWithLifecycle()
    // A newly added library folder: only Reading “…” with its count and bar, once its books are counted.
    if (reading) { progress?.let { com.example.readalong.ui.library.ReadingFolderLine(it) }; return }
    if (busy || message != null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(tr(it), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun ImportPriorityPrompt(model: ImportViewModel) {
    val pending by model.priority.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val adding by model.addedBook.collectAsStateWithLifecycle()
    val epubUploaded by model.epubUploaded.collectAsStateWithLifecycle()
    adding?.let {
        ReadAlongSourceDialog(busy, message, hasEpub = epubUploaded, onDismiss = model::finishAdding,
            onStart = model::startAdded)
    }
    pending.firstOrNull()?.takeIf { adding == null }?.let { book ->
        key(book.id) {
            val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.example.readalong.ReadAlongApplication
            val full by remember(book.id) { app?.bookRepository?.observeBook(book.id) ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsStateWithLifecycle(null)
            ReadAlongSourceDialog(busy, message, hasEpub = book.folderEpub, onDismiss = { if (!busy) model.skipPriority() },
                onStart = model::startPriority, bookTitle = book.title, durationMs = book.durationMs, book = full)
        }
    }
}

@Composable
internal fun ReadingChoice(title: String, subtitle: String, art: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.semantics { this.selected = selected; role = androidx.compose.ui.semantics.Role.RadioButton }, shape = androidx.compose.foundation.shape.RoundedCornerShape(com.example.readalong.ui.theme.Corners.large),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .3f))) {
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.example.readalong.ui.components.ReadAlongArtwork(art, Modifier.fillMaxWidth().height(76.dp), matchColor = MaterialTheme.colorScheme.primary)
            Text(tr(title), modifier = Modifier.weight(1f).fillMaxWidth(), style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(tr(subtitle), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
