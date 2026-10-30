package com.example.readalong.ui.audible

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.example.readalong.audible.*
import com.example.readalong.data.audible.AudibleDownload
import com.example.readalong.data.audible.AudibleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Import from Audible" in the GitHub build: sign in on Amazon's own page, pick books from your Audible
 * library, and ReadAlong downloads, unlocks and imports them. The engine is LibriSync's, ported to Kotlin.
 */
@Composable
fun AudibleImportEntry(onChooseFolder: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AudibleImportScreen(onDismiss, onChooseFolder) }
    }
}

@Composable
private fun AudibleImportScreen(onClose: () -> Unit, onChooseFolder: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AudibleStore.get(context) }
    val account by store.account.collectAsStateWithLifecycle()
    var login by remember { mutableStateOf<AudibleAuth.Login?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var registering by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (login != null) login = null else onClose() }) { Text(if (login != null) "Cancel" else "Close") }
            Text("Import from Audible", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
        val current = account
        val pending = login
        when {
            registering -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(); Text("Connecting ReadAlong to your Audible account…")
                }
            }
            pending != null -> AmazonSignIn(pending) { redirect ->
                login = null
                registering = true
                scope.launch {
                    try { store.save(withContext(Dispatchers.IO) { AudibleAuth.register(pending, redirect) }); error = null }
                    catch (failure: Exception) { error = failure.message ?: "Signing in didn't finish. Try again." }
                    finally { registering = false }
                }
            }
            current == null -> SignInIntro(error) { locale -> error = null; login = AudibleAuth.startLogin(locale) }
            else -> AudibleLibrary(store, current, onChooseFolder)
        }
    }
    BackHandler(enabled = login != null) { login = null }
}

@Composable
private fun SignInIntro(error: String?, onSignIn: (AudibleLocale) -> Unit) {
    var locale by remember { mutableStateOf(AudibleLocale.of(java.util.Locale.getDefault().country.lowercase().let { if (it == "gb") "uk" else it })) }
    var choosing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Bring your Audible audiobooks into ReadAlong", style = MaterialTheme.typography.headlineSmall)
        Text("Sign in on Amazon's page. ReadAlong is added to your Amazon account as a device, then shows the books you own. " +
            "The books you pick are downloaded, turned into ordinary M4B files on this phone, and added to your library, ready for their ReadAlong.")
        Text("Your password goes only to Amazon. Nothing is uploaded anywhere else.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { choosing = true }) { Text("Audible store: ${locale.label}") }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                AudibleLocale.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { locale = option; choosing = false }) }
            }
        }
        Button(onClick = { onSignIn(locale) }) { Text("Sign in with Amazon") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** Amazon's sign-in page. The final redirect carries the one-time code; it's caught here and never loaded. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AmazonSignIn(login: AudibleAuth.Login, onFinished: (String) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    val finished = rememberUpdatedState(onFinished)
    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = IPHONE_SAFARI
                var done = false
                fun intercept(url: String?): Boolean {
                    if (url == null || done || !AudibleAuth.isFinalRedirect(url)) return false
                    done = true
                    stopLoading()
                    finished.value(url)
                    return true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = intercept(request.url.toString())
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { if (!intercept(url)) loading = true }
                    override fun onPageFinished(view: WebView, url: String?) { loading = false }
                }
                loadUrl(login.url)
            }
        }, onRelease = { it.stopLoading(); it.destroy() })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

@Composable
private fun AudibleLibrary(store: AudibleStore, account: AudibleAccount, onChooseFolder: () -> Unit) {
    val library by store.library.collectAsStateWithLifecycle()
    val downloads by store.downloads.collectAsStateWithLifecycle()
    var syncing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var signingOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun sync() {
        if (syncing) return
        syncing = true
        scope.launch {
            try {
                val books = withContext(Dispatchers.IO) { AudibleClient(account, store::save).library() }
                store.saveLibrary(books)
                message = if (books.isEmpty()) "There are no audiobooks in this Audible library yet." else null
            } catch (failure: Exception) { message = failure.message ?: "Couldn't load your Audible library." }
            finally { syncing = false }
        }
    }
    LaunchedEffect(account.customerId) { if (library.isEmpty()) sync() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Signed in${if (account.customerName.isNotEmpty()) " as ${account.customerName}" else ""} · ${account.locale.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = ::sync, enabled = !syncing) { Text(if (syncing) "Refreshing…" else "Refresh library") }
                TextButton(onClick = { signingOut = true }) { Text("Sign out") }
            }
            // Books already downloaded elsewhere (LibriSync, Libation, a computer) come in as a library folder.
            OutlinedButton(onClick = onChooseFolder) { Text("Add audiobook folder") }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search your Audible library") }, singleLine = true)
            if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val shown = remember(library, query) { library.filter { query.isBlank() || query.lowercase() in "${it.title} ${it.authors}".lowercase() } }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shown, key = { it.asin }) { book -> AudibleRow(book, downloads[book.asin]) { store.download(book.asin) } }
        }
    }
    if (signingOut) AlertDialog(onDismissRequest = { signingOut = false }, title = { Text("Sign out of Audible?") },
        text = { Text("ReadAlong is removed from your Amazon devices. Books you've already imported stay in your library.") },
        confirmButton = { TextButton(onClick = {
            signingOut = false
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { AudibleAuth.deregister(account) } }
                CookieManager.getInstance().removeAllCookies(null)
                store.signOut()
            }
        }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { signingOut = false }) { Text("Cancel") } })
}

@Composable
private fun AudibleRow(book: AudibleBook, state: AudibleDownload?, onDownload: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(book.coverUrl, null, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.authors.isNotEmpty()) Text(book.authors, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (state) {
                is AudibleDownload.Running -> {
                    LinearProgressIndicator({ state.fraction }, Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text(when (state.stage) {
                        AudibleDownloader.Stage.LICENSING -> "Asking Audible…"
                        AudibleDownloader.Stage.DOWNLOADING -> "Downloading ${(state.fraction * 100).toInt()}%"
                        AudibleDownloader.Stage.UNLOCKING -> "Unlocking…"
                    }, style = MaterialTheme.typography.bodySmall)
                }
                is AudibleDownload.Failed -> Text(state.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                AudibleDownload.Queued -> Text("Waiting to download", style = MaterialTheme.typography.bodySmall)
                AudibleDownload.Done -> Text("In your library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                null -> if (book.runtimeMinutes > 0) Text("${book.runtimeMinutes / 60} h ${book.runtimeMinutes % 60} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (state) {
            null, is AudibleDownload.Failed -> FilledTonalButton(onClick = onDownload) { Text(if (state == null) "Download" else "Retry") }
            else -> Unit
        }
    }
}

private const val IPHONE_SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
