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

/**
 * Signing in: Amazon's page opens in a ReadAlong window, which catches Amazon's final address by itself, so
 * there is nothing to copy or paste. Where that window can't open, the system browser is used and the address
 * is picked up from the clipboard as soon as it's copied; pasting it by hand stays as a last resort.
 */
@Composable
private fun AudibleSignIn(app: AppState) {
    val scope = rememberCoroutineScope()
    var locale by remember { mutableStateOf(AudibleLocale.of(java.util.Locale.getDefault().country.lowercase().let { if (it == "gb") "uk" else it })) }
    var choosing by remember { mutableStateOf(false) }
    // null: not started; true: in ReadAlong's sign-in window; false: in the system browser.
    var inWindow by remember { mutableStateOf<Boolean?>(null) }
    var redirect by remember { mutableStateOf("") }
    var pasting by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun finish(address: String) {
        if (working) return
        working = true; error = null
        scope.launch {
            try { app.audible.finishLogin(address) }
            catch (failure: Exception) { error = failure.message ?: "Signing in didn't finish. Try again."; inWindow = null }
            finally { working = false }
        }
    }
    fun signIn() {
        error = null; redirect = ""
        val address = app.audible.startLogin(locale)
        val opened = AudibleSignInWindow.open(address, "Sign in to Amazon · ReadAlong",
            onRedirect = { scope.launch { finish(it) } }, onClosed = { scope.launch { if (!working) inWindow = null } })
        inWindow = opened
        if (!opened && !openBrowser(address)) { error = "Your browser didn't open. Copy the link below into your browser."; pasting = true }
    }
    // The system browser fallback: watch the clipboard for Amazon's final address.
    if (inWindow == false) LaunchedEffect(Unit) {
        // An address left on the clipboard from an earlier sign-in belongs to another attempt.
        val stale = AudibleSignInWindow.redirectOnClipboard()
        while (true) {
            AudibleSignInWindow.redirectOnClipboard()?.takeIf { it != stale }?.let { if (!working) finish(it) }
            kotlinx.coroutines.delay(700)
        }
    }
    DisposableEffect(Unit) { onDispose { AudibleSignInWindow.close() } }
    Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Sign in with your Amazon account to see your Audible library and download your books.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                PillButton("Store: ${locale.label}", { choosing = true }, filled = false, enabled = !working)
                DropdownMenu(choosing, { choosing = false }) {
                    AudibleLocale.entries.forEach { option -> DropdownMenuItem({ Text(option.label) }, { locale = option; choosing = false; AudibleSignInWindow.close(); inWindow = null }) }
                }
            }
            PillButton(if (working) "Signing in…" else if (inWindow != null) "Start again" else "Sign in to Amazon", { signIn() }, enabled = !working)
            if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        when (inWindow) {
            true -> Text("Sign in in the window that opened. ReadAlong finishes by itself once Amazon is done.", style = MaterialTheme.typography.bodyMedium)
            false -> Text("Sign in in your browser. When Amazon ends on a \"page not found\", copy that page's address (Ctrl+L, then Ctrl+C) and ReadAlong takes it from there.",
                style = MaterialTheme.typography.bodyMedium)
            null -> {}
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (inWindow != null || pasting) {
            if (!pasting) TextButton({ pasting = true }) { Text("Paste the address instead") }
            else {
                app.audible.login?.url?.let { address ->
                    val clipboard = LocalClipboardManager.current
                    PillButton("Copy sign-in link", { clipboard.setText(AnnotatedString(address)) }, filled = false)
                }
                OutlinedTextField(redirect, { redirect = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !working,
                    placeholder = { Text("https://www.amazon…/ap/maplanding?…") })
                PillButton("Finish signing in", { finish(redirect) }, enabled = redirect.isNotBlank() && !working, filled = false)
            }
        }
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
internal fun openBrowser(url: String): Boolean = runCatching {
    val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
    if (desktop?.isSupported(java.awt.Desktop.Action.BROWSE) == true) { desktop.browse(URI(url)); true }
    else { ProcessBuilder("xdg-open", url).start(); true }
}.getOrDefault(false)
