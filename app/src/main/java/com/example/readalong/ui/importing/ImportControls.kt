package com.example.readalong.ui.importing

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
import androidx.compose.ui.Alignment
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
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { uri -> model.folder(uri, FolderMode.ONE_BOOK) } }
    val epub = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::addEpub) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) model.files(it) }
    IconButton(onClick = { choosing = true }, enabled = !busy) {
        Icon(androidx.compose.ui.res.painterResource(com.example.readalong.R.drawable.ic_add), "Add audiobook")
    }

    if (choosing) com.example.readalong.ui.components.IllustratedDialog("Add audiobook", { choosing = false }, actions = {
        TextButton(onClick = { choosing = false }) { Text("Cancel") }
    }) {
        IllustratedOption("Audiobook folder", "Select a folder containing multiple audio files.", com.example.readalong.R.drawable.art_folder, true) { choosing = false; folder.launch(null) }
        IllustratedOption("Audio files", "Select a single audio file or the parts of one book.", com.example.readalong.R.drawable.art_audio_file, false) {
            choosing = false; files.launch(arrayOf("audio/*", "application/octet-stream", "video/mp4"))
        }
    }

}

@Composable
fun FoldersScreen(model: ImportViewModel, onBack: () -> Unit) {
    val folders by model.folders.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<com.example.readalong.data.local.FolderEntity?>(null) }
    var audible by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> pendingUri = uri?.toString() }
    Scaffold { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { TextButton(onClick = onBack) { Text("Back to library") } }
            item { Text("Library folders", style = MaterialTheme.typography.headlineLarge) }
            item { Text("Library folders are scanned automatically whenever you return home. Audio stays in the folder you choose.") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { picker.launch(null) }, enabled = !busy) { Text("Add folder") }
                    OutlinedButton(onClick = { audible = true }, enabled = !busy) { Text("Import from Audible") }
                }
            }
            item { if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()); message?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
            if (folders.none { it.mode == FolderMode.LIBRARY.name }) item { Text("No folders added yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(folders.filter { it.mode == FolderMode.LIBRARY.name }, key = { it.uri }) { folder ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(folder.name, style = MaterialTheme.typography.titleLarge)
                        Text(if (folder.mode == FolderMode.ONE_BOOK.name) "One audiobook" else "Library folder · each subfolder is a book", style = MaterialTheme.typography.bodySmall)
                        Text(folder.uri, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        folder.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row {
                            TextButton(onClick = { model.rescan(folder) }, enabled = !busy) { Text("Rescan") }
                            TextButton(onClick = { removing = folder }, enabled = !busy) { Text("Remove access") }
                        }
                    }
                }
            }
        }
    }
    pendingUri?.let { uri ->
        LaunchedEffect(uri) { model.folder(Uri.parse(uri), FolderMode.LIBRARY); pendingUri = null }
    }
    if (audible) AudibleImportDialog(onChooseFolder = { audible = false; picker.launch(null) }, onDismiss = { audible = false })
    removing?.let { folder ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove folder access?") },
            text = { Text("Are you sure? This removes every book in this folder from your library and deletes its saved metadata, readable text and transcription progress. Original audio files stay on your device.") },
            confirmButton = { TextButton(onClick = { model.remove(folder); removing = null }) { Text("Remove access") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

@Composable
fun ImportStatus(model: ImportViewModel) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    if (busy || message != null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
        com.example.readalong.ui.components.IllustratedDialog("Read along with your audiobook?", { if (!busy) model.skipPriority() },
            artwork = { com.example.readalong.ui.components.ReadAlongArtwork() }, actions = {
                OutlinedButton(enabled = !busy, onClick = { model.choosePriority(false) }) { Text("Later") }
                Button(enabled = !busy, onClick = { model.choosePriority(true) }, modifier = Modifier.weight(1f)) { Text("Create ReadAlong", maxLines = 1, softWrap = false) }
            }) {
            Text(book.title, style = MaterialTheme.typography.titleMedium)
            Text("Turn your audiobook into readable text that highlights each sentence as you listen.")
            Text("Created locally on your device. This may take a while; you can keep listening while it works.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun LibrarySetupPrompt(model: ImportViewModel, onNext: () -> Unit) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { model.folder(it, FolderMode.LIBRARY, onNext) }
    }
    com.example.readalong.ui.components.IllustratedDialog("Your audiobook library", {},
        artwork = { com.example.readalong.ui.components.ReadAlongArtwork(com.example.readalong.R.drawable.art_folder) }, actions = {
            TextButton(enabled = !busy, onClick = onNext, modifier = Modifier.weight(1f)) { Text("Later") }
            Button(enabled = !busy, onClick = { folder.launch(null) }, modifier = Modifier.weight(1f)) { Text("Set folder") }
        }) {
        Text("Choose your audiobook library folder. Each book’s subfolder appears in your library, and new books are found when you return home.")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun IllustratedOption(title: String, subtitle: String, art: Int, prominent: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        color = if (prominent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .25f))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            com.example.readalong.ui.components.ReadAlongArtwork(art, Modifier.size(80.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
internal fun ReadingChoice(title: String, subtitle: String, art: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .3f))) {
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.example.readalong.ui.components.ReadAlongArtwork(art, Modifier.fillMaxWidth().height(76.dp))
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RadioButton(selected, onClick = null)
        }
    }
}

/** Latest LibriSync release, the app that downloads a person's own Audible purchases to a folder. */
private const val LIBRISYNC_RELEASES = "https://github.com/Promises/LibriSync/releases/latest"

@Composable
private fun AudibleImportDialog(onChooseFolder: () -> Unit, onDismiss: () -> Unit) {
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Import from Audible") }, text = {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ReadAlong can't sign in to Audible itself. A free app called LibriSync downloads the audiobooks you've bought to a folder on your phone, and ReadAlong reads them from there.")
            AudibleStep(1, "Install LibriSync", "Download the latest version from its release page.") {
                TextButton(onClick = { runCatching { links.openUri(LIBRISYNC_RELEASES) } }, contentPadding = PaddingValues(0.dp)) { Text("Open LibriSync releases") }
            }
            AudibleStep(2, "Sign in to your Audible account", "Open LibriSync and sign in with the account you buy audiobooks with.")
            AudibleStep(3, "Choose where books are saved", "In LibriSync's settings, set the download folder to a new folder called \u201cAudible audiobooks\u201d. Saving each book as a single file works best.")
            AudibleStep(4, "Download your books", "Pick the audiobooks you want in LibriSync and download them.")
            AudibleStep(5, "Add the folder here", "Tap \u201cChoose Audible folder\u201d below and select \u201cAudible audiobooks\u201d. Your books appear in your library, and new downloads show up each time you come back to ReadAlong.")
            Text("Only your own purchases are downloaded, and they stay on your phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { Button(onClick = onChooseFolder) { Text("Choose Audible folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun AudibleStep(number: Int, title: String, body: String, extra: (@Composable () -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra?.invoke()
        }
    }
}
