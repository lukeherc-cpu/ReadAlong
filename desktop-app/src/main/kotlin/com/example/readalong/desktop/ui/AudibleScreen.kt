package com.example.readalong.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.readalong.audible.AudibleBook
import com.example.readalong.audible.AudibleDownloader
import com.example.readalong.audible.AudibleLocale
import com.example.readalong.desktop.core.AudibleDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/** "Import from Audible": Libation's workflow inside ReadAlong. Only signing in happens in the web browser. */
@Composable
fun AudibleScreen(app: AppState) {
    val audible = app.audible
    val account by audible.account.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton("ic_back", "Back (Esc)", { app.back() })
            Spacer(Modifier.width(10.dp))
            Text("Import from Audible", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        }
        val current = account
        if (current == null) AudibleSignIn(app) else AudibleLibrary(app, current.customerName, current.locale.label)
    }
}

@Composable
private fun AudibleSignIn(app: AppState) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var locale by remember { mutableStateOf(AudibleLocale.of(java.util.Locale.getDefault().country.lowercase().let { if (it == "gb") "uk" else it })) }
    var choosing by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(app.audible.login?.url) }
    var redirect by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Bring your Audible audiobooks into ReadAlong", style = MaterialTheme.typography.titleLarge)
        Text("ReadAlong signs in as a device on your Amazon account, the way Libation does. The books you pick are downloaded, " +
            "turned into ordinary M4B files on this computer, and added to your library and the queue.")
        Text("1. Choose your Audible store and open Amazon's sign-in page in your browser.", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                PillButton("Store: ${locale.label}", { choosing = true }, filled = false)
                DropdownMenu(choosing, { choosing = false }) {
                    AudibleLocale.entries.forEach { option -> DropdownMenuItem({ Text(option.label) }, { locale = option; choosing = false; url = null }) }
                }
            }
            PillButton("Open Amazon sign-in", {
                val address = app.audible.startLogin(locale)
                url = address
                error = if (openBrowser(address)) null else "Your browser didn't open. Copy the link and paste it into your browser."
            })
            url?.let { address -> PillButton("Copy link", { clipboard.setText(AnnotatedString(address)) }, filled = false) }
        }
        Text("2. Sign in. Amazon ends on a page that says it can't be found. That's expected: copy that page's whole address from the address bar and paste it here.",
            style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(redirect, { redirect = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = url != null && !working,
            placeholder = { Text("https://www.amazon…/ap/maplanding?…") })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(if (working) "Signing in…" else "Finish signing in", {
                working = true; error = null
                scope.launch {
                    try { app.audible.finishLogin(redirect) } catch (failure: Exception) { error = failure.message ?: "Signing in didn't finish. Try again." }
                    finally { working = false }
                }
            }, enabled = url != null && redirect.isNotBlank() && !working)
            if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Your password goes only to Amazon. Nothing is uploaded anywhere else.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AudibleLibrary(app: AppState, name: String, store: String) {
    val audible = app.audible
    val library by audible.library.collectAsState()
    val downloads by audible.downloads.collectAsState()
    val syncing by audible.syncing.collectAsState()
    var query by remember { mutableStateOf("") }
    var signingOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (library.isEmpty()) audible.sync() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Signed in${if (name.isNotEmpty()) " as $name" else ""} · $store", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (downloads.values.any { it is AudibleDownload.Queued || it is AudibleDownload.Running }) PillButton("Stop downloads", audible::cancelDownloads, filled = false)
        PillButton(if (syncing) "Refreshing…" else "Refresh library", audible::sync, filled = false, enabled = !syncing)
        PillButton("Sign out", { signingOut = true }, filled = false)
    }
    // Books already downloaded elsewhere (Libation, LibriSync, another computer) come in as a library folder.
    PillButton("Add audiobook folder", { chooseFolder()?.let { app.addPaths(listOf(it)) } }, Modifier.padding(top = 12.dp), icon = "ic_folder", filled = false)
    OutlinedTextField(query, { query = it }, Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(vertical = 12.dp), singleLine = true,
        placeholder = { Text("Search your Audible library") })
    if (syncing) LinearProgressIndicator(Modifier.widthIn(max = 860.dp).fillMaxWidth())
    val shown = remember(library, query) { library.filter { query.isBlank() || query.lowercase() in "${it.title} ${it.authors}".lowercase() } }
    val list = rememberLazyListState()
    LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxWidth().smoothWheelScroll(list), state = list,
        verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(shown, key = { it.asin }) { book -> AudibleRow(book, downloads[book.asin]) { audible.download(book.asin) } }
    }
    if (signingOut) AlertDialog({ signingOut = false }, title = { Text("Sign out of Audible?") },
        text = { Text("ReadAlong is removed from your Amazon devices. Books you've already imported stay in your library.") },
        confirmButton = { TextButton({ signingOut = false; scope.launch { audible.signOut() } }) { Text("Sign out") } },
        dismissButton = { TextButton({ signingOut = false }) { Text("Cancel") } })
}

@Composable
private fun AudibleRow(book: AudibleBook, state: AudibleDownload?, onDownload: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val cover by produceState<ImageBitmap?>(null, book.coverUrl) { value = book.coverUrl?.let { remoteCover(it) } }
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            cover?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.authors.isNotEmpty()) Text(book.authors, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (state) {
                is AudibleDownload.Running -> {
                    ProgressLine(state.fraction, Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text(when (state.stage) {
                        AudibleDownloader.Stage.LICENSING -> "Asking Audible…"
                        AudibleDownloader.Stage.DOWNLOADING -> "Downloading ${(state.fraction * 100).toInt()}%"
                        AudibleDownloader.Stage.UNLOCKING -> "Unlocking…"
                    }, style = MaterialTheme.typography.bodySmall)
                }
                is AudibleDownload.Failed -> Text(state.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                AudibleDownload.Queued -> Text("Waiting to download", style = MaterialTheme.typography.bodySmall)
                AudibleDownload.Done -> Text("In your library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                null -> if (book.runtimeMinutes > 0) Text("${book.runtimeMinutes / 60}h ${book.runtimeMinutes % 60}m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state == null || state is AudibleDownload.Failed) PillButton(if (state == null) "Download" else "Retry", onDownload, filled = false)
    }
}

private val remoteCovers = object : LinkedHashMap<String, ImageBitmap>(64, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 300
}

private suspend fun remoteCover(url: String): ImageBitmap? = synchronized(remoteCovers) { remoteCovers[url] } ?: withContext(Dispatchers.IO) {
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(URI(url).toURL().readBytes()).toComposeImageBitmap() }.getOrNull()
        ?.also { synchronized(remoteCovers) { remoteCovers[url] = it } }
}

/** The default browser; xdg-open where Java's desktop support is missing (some Linux desktops and the Flatpak). */
private fun openBrowser(url: String): Boolean = runCatching {
    val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
    if (desktop?.isSupported(java.awt.Desktop.Action.BROWSE) == true) { desktop.browse(URI(url)); true }
    else { ProcessBuilder("xdg-open", url).start(); true }
}.getOrDefault(false)
