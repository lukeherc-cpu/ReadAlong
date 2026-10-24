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
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var download by remember { mutableStateOf(LibriSyncDownloads.current) }
    var installed by remember { mutableStateOf(libriSyncInstalled(context)) }
    // Coming back from Android's installer (or settings): check again.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { installed = libriSyncInstalled(context) }
    // While the APK downloads, follow the system download manager's progress.
    LaunchedEffect((download as? LibriSyncDownload.Downloading)?.id) {
        val id = (download as? LibriSyncDownload.Downloading)?.id ?: return@LaunchedEffect
        while (true) {
            val next = queryLibriSyncDownload(context, id)
            download = next; LibriSyncDownloads.current = next
            if (next !is LibriSyncDownload.Downloading) break
            kotlinx.coroutines.delay(250)
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Import from Audible") }, text = {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ReadAlong can't sign in to Audible itself. A free app called LibriSync downloads the audiobooks you've bought to a folder on your phone, and ReadAlong reads them from there.")
            AudibleStep(1, "Install LibriSync", if (installed) "LibriSync is installed." else
                "Tap \u201cDownload LibriSync\u201d and wait for the bar to fill. Then tap \u201cInstall LibriSync\u201d and choose Install. If Android asks, allow ReadAlong to install apps, come back, and tap \u201cInstall LibriSync\u201d again.") {
                LibriSyncButton(installed, download, onDownload = {
                    if (download !is LibriSyncDownload.Finding && download !is LibriSyncDownload.Downloading) scope.launch {
                        download = LibriSyncDownload.Finding
                        download = runCatching { startLibriSyncDownload(context) }.getOrElse { LibriSyncDownload.Failed }
                    }
                }, onInstall = { id -> installLibriSync(context, id) }, onOpen = {
                    context.packageManager.getLaunchIntentForPackage(LIBRISYNC_PACKAGE)?.let { runCatching { context.startActivity(it) } }
                }, onReleasePage = { runCatching { links.openUri(LIBRISYNC_RELEASES) } })
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

private const val LIBRISYNC_PACKAGE = "tech.henning.librisync"

private sealed interface LibriSyncDownload {
    data object Idle : LibriSyncDownload
    data object Finding : LibriSyncDownload
    data class Downloading(val id: Long, val version: String, val fraction: Float?) : LibriSyncDownload
    data class Downloaded(val id: Long, val version: String) : LibriSyncDownload
    data object Failed : LibriSyncDownload
}

/** Keeps a running download across closing and reopening the dialog. */
private object LibriSyncDownloads { var current: LibriSyncDownload = LibriSyncDownload.Idle }

private fun libriSyncInstalled(context: android.content.Context) = context.packageManager.getLaunchIntentForPackage(LIBRISYNC_PACKAGE) != null

@Composable
private fun LibriSyncButton(installed: Boolean, download: LibriSyncDownload, onDownload: () -> Unit, onInstall: (Long) -> Unit, onOpen: () -> Unit, onReleasePage: () -> Unit) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            installed -> Button(onClick = onOpen) { Text("Open LibriSync") }
            download is LibriSyncDownload.Downloaded -> Button(onClick = { onInstall(download.id) }) { Text("Install LibriSync") }
            download is LibriSyncDownload.Downloading -> {
                val shown by androidx.compose.animation.core.animateFloatAsState(download.fraction ?: 0f, label = "LibriSync download")
                if (download.fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp))
                else LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth().height(6.dp))
                Text("Downloading LibriSync ${download.version}" + (download.fraction?.let { " \u00b7 ${(it * 100).toInt()}%" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            download is LibriSyncDownload.Finding -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp))
                Text("Finding the latest version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Button(onClick = onDownload) { Text("Download LibriSync") }
        }
        if (download is LibriSyncDownload.Failed && !installed) {
            Text("The download didn't work. Check your connection and try again, or get it from the release page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onReleasePage, contentPadding = PaddingValues(0.dp)) { Text("Open release page") }
        }
    }
}

/**
 * Starts downloading the APK of LibriSync's latest stable release (GitHub's "latest" skips drafts
 * and pre-releases) with the system download manager. Picks the universal APK, else one for this
 * phone's CPU.
 */
private suspend fun startLibriSyncDownload(context: android.content.Context): LibriSyncDownload = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val connection = (java.net.URL("https://api.github.com/repos/Promises/LibriSync/releases/latest").openConnection() as java.net.HttpURLConnection).apply {
        setRequestProperty("Accept", "application/vnd.github+json"); connectTimeout = 15_000; readTimeout = 15_000
    }
    val release = try {
        check(connection.responseCode == 200) { "GitHub returned ${connection.responseCode}" }
        org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } finally { connection.disconnect() }
    val assets = release.getJSONArray("assets").let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
        .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
    val asset = assets.firstOrNull { it.getString("name").contains("universal", ignoreCase = true) }
        ?: android.os.Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> assets.firstOrNull { it.getString("name").contains(abi, ignoreCase = true) } }
        ?: assets.singleOrNull()
        ?: error("No APK in the latest release")
    val version = release.optString("tag_name").ifBlank { "latest" }
    val request = android.app.DownloadManager.Request(android.net.Uri.parse(asset.getString("browser_download_url")))
        .setTitle("LibriSync $version")
        .setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
        .setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, "LibriSync-$version.apk")
    val id = context.getSystemService(android.app.DownloadManager::class.java).enqueue(request)
    LibriSyncDownload.Downloading(id, version, null).also { LibriSyncDownloads.current = it }
}

private fun queryLibriSyncDownload(context: android.content.Context, id: Long): LibriSyncDownload {
    val manager = context.getSystemService(android.app.DownloadManager::class.java)
    val version = (LibriSyncDownloads.current as? LibriSyncDownload.Downloading)?.version ?: "latest"
    manager.query(android.app.DownloadManager.Query().setFilterById(id))?.use { cursor ->
        if (!cursor.moveToFirst()) return LibriSyncDownload.Failed
        val status = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
        val done = cursor.getLong(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total = cursor.getLong(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        return when (status) {
            android.app.DownloadManager.STATUS_SUCCESSFUL -> LibriSyncDownload.Downloaded(id, version)
            android.app.DownloadManager.STATUS_FAILED -> LibriSyncDownload.Failed
            else -> LibriSyncDownload.Downloading(id, version, if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null)
        }
    }
    return LibriSyncDownload.Failed
}

/** Opens Android's installer for the downloaded APK, or first the "install unknown apps" setting. */
private fun installLibriSync(context: android.content.Context, id: Long) {
    if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))) }
        return
    }
    val uri = context.getSystemService(android.app.DownloadManager::class.java).getUriForDownloadedFile(id) ?: return
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
