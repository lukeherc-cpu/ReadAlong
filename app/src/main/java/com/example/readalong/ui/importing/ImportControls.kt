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
    IconButton(onClick = { choosing = true }, enabled = !busy) {
        Icon(androidx.compose.ui.res.painterResource(com.example.readalong.R.drawable.ic_add), "Add a new audiobook")
    }

    if (choosing) com.example.readalong.ui.components.IllustratedDialog("Add a new audiobook", { choosing = false }, actions = {
        TextButton(onClick = { choosing = false }) { Text("Cancel") }
    }) {
        // The same buttons, icons and shades as the welcome page's choices.
        val shades = sourceShades()
        SourceButton(shades[0], com.example.readalong.R.drawable.ic_bookshelf, "Add audiobooks", "Choose a folder with one book or your whole library", true, large = true) { choosing = false; add() }
        SourceButton(shades[2], com.example.readalong.R.drawable.ic_play, "Add audio file", "One file that holds a whole audiobook", true, large = true) {
            choosing = false; files.launch(arrayOf("audio/*", "application/octet-stream", "video/mp4"))
        }
    }
}

@Composable
fun ImportStatus(model: ImportViewModel) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    if (busy || message != null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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
            Text(title, modifier = Modifier.weight(1f).fillMaxWidth(), style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(subtitle, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Latest LibriSync release, the app that downloads a person's own Audible purchases to a folder. */
private const val LIBRISYNC_RELEASES = "https://github.com/Promises/LibriSync/releases/latest"

/** The LibriSync instructions. Screens open [com.example.readalong.ui.audible.AudibleImportEntry], which shows this in the Play build only. */
@Composable
internal fun AudibleImportDialog(onChooseFolder: () -> Unit, onDismiss: () -> Unit) {
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val download by LibriSyncDownloads.state.collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf(libriSyncInstalled(context)) }
    // Coming back from Android's installer (or settings): check again.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { installed = libriSyncInstalled(context) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Import from Audible") }, text = {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ReadAlong can't sign in to Audible itself. A free app called LibriSync downloads the audiobooks you've bought to a folder on your phone, and ReadAlong reads them from there.")
            AudibleStep(1, "Install LibriSync", if (installed) "LibriSync is installed." else
                "Tap \u201cDownload LibriSync\u201d and wait for the bar to fill. Then tap \u201cInstall LibriSync\u201d and choose Install. If Android asks, allow ReadAlong to install apps, come back, and tap \u201cInstall LibriSync\u201d again.") {
                LibriSyncButton(installed, download, onDownload = {
                    LibriSyncDownloads.start(context.applicationContext)
                }, onInstall = { file -> installLibriSync(context, file) }, onOpen = {
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
    data class Downloading(val version: String, val fraction: Float?) : LibriSyncDownload
    data class Downloaded(val file: java.io.File, val version: String) : LibriSyncDownload
    data class Failed(val reason: String) : LibriSyncDownload
}

private fun libriSyncInstalled(context: android.content.Context) = context.packageManager.getLaunchIntentForPackage(LIBRISYNC_PACKAGE) != null

/**
 * Downloads the APK of LibriSync's latest stable release (GitHub's "latest" skips drafts and
 * pre-releases) into ReadAlong's cache, with progress here and in a notification. Runs on its own
 * scope so closing the dialog doesn't stop it. Picks the APK for this phone's CPU, else the universal one.
 */
private object LibriSyncDownloads {
    val state = kotlinx.coroutines.flow.MutableStateFlow<LibriSyncDownload>(LibriSyncDownload.Idle)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private const val CHANNEL = "librisync-download"
    private const val NOTIFICATION = 7310

    fun start(context: android.content.Context) {
        val now = state.value
        if (now is LibriSyncDownload.Finding || now is LibriSyncDownload.Downloading) return
        state.value = LibriSyncDownload.Finding
        scope.launch {
            state.value = try { download(context) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                android.util.Log.w("ReadAlong", "LibriSync download failed: ${failure.javaClass.simpleName}: ${failure.message}")
                notify(context, "LibriSync download failed", null, done = true)
                LibriSyncDownload.Failed(failure.message ?: failure.javaClass.simpleName)
            }
        }
    }

    private fun open(url: String): java.net.HttpURLConnection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        setRequestProperty("User-Agent", "ReadAlong"); connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true
    }

    private fun download(context: android.content.Context): LibriSyncDownload {
        val meta = open("https://api.github.com/repos/Promises/LibriSync/releases/latest").apply { setRequestProperty("Accept", "application/vnd.github+json") }
        val release = try {
            check(meta.responseCode == 200) { "GitHub answered ${meta.responseCode}" }
            org.json.JSONObject(meta.inputStream.bufferedReader().use { it.readText() })
        } finally { meta.disconnect() }
        val assets = release.getJSONArray("assets").let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
            .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
        // The APK for this phone's CPU is about a quarter of the universal one (59 MB vs 203 MB for v0.0.31).
        val asset = android.os.Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> assets.firstOrNull { it.getString("name").contains(abi, ignoreCase = true) } }
            ?: assets.firstOrNull { it.getString("name").contains("universal", ignoreCase = true) }
            ?: assets.singleOrNull()
            ?: error("The latest release has no APK")
        val version = release.optString("tag_name").ifBlank { "latest" }
        val folder = java.io.File(context.cacheDir, "downloads").apply { mkdirs() }
        val target = java.io.File(folder, "LibriSync-$version.apk")
        val partial = java.io.File(folder, "LibriSync-$version.apk.part")
        state.value = LibriSyncDownload.Downloading(version, null)
        // GitHub redirects release assets to its file host; follow it by hand in case it changes protocol.
        var connection = open(asset.getString("browser_download_url"))
        repeat(5) {
            val code = connection.responseCode
            if (code in 300..399) { val next = connection.getHeaderField("Location"); connection.disconnect(); connection = open(next) } else return@repeat
        }
        try {
            check(connection.responseCode == 200) { "Download answered ${connection.responseCode}" }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.optLong("size").takeIf { it > 0 }
            var read = 0L; var lastShown = -1
            connection.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    output.write(buffer, 0, n); read += n
                    val fraction = total?.let { (read.toFloat() / it).coerceIn(0f, 1f) }
                    val percent = fraction?.let { (it * 100).toInt() } ?: -1
                    if (percent != lastShown) { lastShown = percent; state.value = LibriSyncDownload.Downloading(version, fraction); notify(context, "Downloading LibriSync $version", fraction, done = false) }
                }
            } }
        } finally { connection.disconnect() }
        check(partial.length() > 0) { "The download was empty" }
        target.delete(); check(partial.renameTo(target)) { "Couldn't save the download" }
        notify(context, "LibriSync downloaded. Open ReadAlong to install it.", 1f, done = true)
        return LibriSyncDownload.Downloaded(target, version)
    }

    private fun notify(context: android.content.Context, text: String, fraction: Float?, done: Boolean) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Downloads", android.app.NotificationManager.IMPORTANCE_LOW))
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val builder = androidx.core.app.NotificationCompat.Builder(context, CHANNEL).setSmallIcon(com.example.readalong.R.drawable.ic_notification)
            .setContentTitle(text).setOnlyAlertOnce(true).setOngoing(!done)
        if (done) builder.setSmallIcon(com.example.readalong.R.drawable.ic_notification).setAutoCancel(true)
        else builder.setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
        runCatching { manager.notify(NOTIFICATION, builder.build()) }
    }
}

@Composable
private fun LibriSyncButton(installed: Boolean, download: LibriSyncDownload, onDownload: () -> Unit, onInstall: (java.io.File) -> Unit, onOpen: () -> Unit, onReleasePage: () -> Unit) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            installed -> Button(onClick = onOpen) { Text("Open LibriSync") }
            download is LibriSyncDownload.Downloaded -> Button(onClick = { onInstall(download.file) }) { Text("Install LibriSync") }
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
            else -> Button(onClick = onDownload) { Text(if (download is LibriSyncDownload.Failed) "Try again" else "Download LibriSync") }
        }
        if (download is LibriSyncDownload.Failed && !installed) {
            Text("The download didn't work (${download.reason}). Check your connection and try again, or get it from the release page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onReleasePage, contentPadding = PaddingValues(0.dp)) { Text("Open release page") }
        }
    }
}

/** Opens Android's installer for the downloaded APK, or first the "install unknown apps" setting. */
private fun installLibriSync(context: android.content.Context, file: java.io.File) {
    if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))) }
        return
    }
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
