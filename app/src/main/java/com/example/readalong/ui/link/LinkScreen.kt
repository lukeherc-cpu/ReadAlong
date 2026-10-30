package com.example.readalong.ui.link

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.R
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.importing.FolderMode
import com.example.readalong.data.link.FolderPair
import com.example.readalong.data.link.LinkSyncWorker
import com.example.readalong.data.link.RemoteFolder
import com.example.readalong.domain.link.LinkCode
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Android 17 asks before an app talks to devices on the home network. */
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * Settings › Link a computer: scan the code ReadAlong Desktop shows, then "Sync your audiobook folders?",
 * then the link's status with Sync now.
 */
@Composable
fun LinkScreen(app: ReadAlongApplication, onBack: () -> Unit) {
    val context = LocalContext.current
    val sync = app.folderLink
    val state by sync.store.state.collectAsStateWithLifecycle()
    val progress by sync.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var offered by remember { mutableStateOf<List<RemoteFolder>?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingAction?.invoke() else error = "ReadAlong needs permission to find devices on your Wi-Fi to reach your computer."
        pendingAction = null
    }
    /** Runs [action] once ReadAlong may use the home network. */
    fun withNetwork(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingAction = action; permission.launch(LOCAL_NETWORK) }
    }
    fun link(text: String) {
        val code = LinkCode.parse(text) ?: run { error = "That isn't a ReadAlong link code. On your computer, open Settings › Phone › Link phone."; return }
        withNetwork {
            scope.launch {
                busy = "Linking to ${code.name}…"; error = null
                try { offered = sync.link(code); choosing = true }
                catch (failure: Exception) { error = failure.message ?: "Couldn't link." }
                finally { busy = null }
            }
        }
    }
    fun scan() {
        error = null
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(::link) }
            .addOnFailureListener { error = "The camera scanner isn't available on this phone. Copy the code on your computer and paste it here instead."; pasting = true }
    }
    fun loadFolders() = withNetwork {
        scope.launch {
            busy = "Asking ${state.computer?.name} for its folders…"; error = null
            try { offered = sync.remoteFolders(); choosing = true }
            catch (failure: Exception) { error = failure.message }
            finally { busy = null }
        }
    }
    // Linked earlier but "Sync your audiobook folders?" was never answered: ask now.
    LaunchedEffect(state.computer != null && !state.foldersAsked) { if (state.computer != null && !state.foldersAsked && offered == null && busy == null) loadFolders() }

    Scaffold { insets -> Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back to settings") }
            Text(if (choosing) "Sync your audiobook folders?" else "Link a computer", style = MaterialTheme.typography.headlineMedium)
        }
        busy?.let { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it) } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val computer = state.computer
        when {
            computer == null -> {
                Text("Keep your audiobooks, ReadAlongs and listening progress the same on this phone and ReadAlong on your computer. " +
                    "They sync directly over your Wi-Fi, encrypted, with no account or cloud.", style = MaterialTheme.typography.bodyLarge)
                Text("On the computer, open ReadAlong › Settings › Phone › Link phone, then scan the code it shows.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = ::scan, enabled = busy == null) { Text("Scan the code") }
                if (!pasting) TextButton(onClick = { pasting = true }) { Text("Paste the code instead") }
                else {
                    var text by remember { mutableStateOf("") }
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Link code") }, singleLine = true)
                    Button(onClick = { link(text) }, enabled = text.isNotBlank() && busy == null) { Text("Link") }
                }
            }
            choosing -> offered?.let { folders ->
                FolderChooser(app, computer.name, folders, state.pairs, onDone = { pairs, sendHere ->
                    scope.launch {
                        busy = "Setting up…"; error = null
                        try {
                            val created = sendHere.map { (uri, name) -> sync.createRemote(name).let { FolderPair(it.id, it.name, uri, name) } }
                            sync.store.update { it.copy(pairs = pairs + created, foldersAsked = true) }
                            choosing = false
                            LinkSyncWorker.schedule(context); LinkSyncWorker.syncNow(context)
                        } catch (failure: Exception) { error = failure.message }
                        finally { busy = null }
                    }
                }, onSkip = { sync.store.update { it.copy(foldersAsked = true) }; choosing = false })
            }
            else -> {
                Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Linked to ${computer.name}", style = MaterialTheme.typography.titleMedium)
                        val last = state.lastSyncAt?.let { "Last synced ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}." } ?: "Not synced yet."
                        Text(listOfNotNull(last, state.lastResult).joinToString(" "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        progress?.let { p ->
                            Text(p.text, style = MaterialTheme.typography.bodyMedium)
                            p.fraction?.let { f -> LinearProgressIndicator({ f }, Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
                Text("Synced folders", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.pairs.isEmpty()) Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.pairs.forEach { pair -> Text("${pair.localName}  ⇄  ${pair.remoteName} on ${computer.name}", style = MaterialTheme.typography.bodyLarge) }
                Text("Syncs when this phone is on Wi-Fi and ReadAlong is open on the computer. Books one side doesn't have are copied over; nothing is ever deleted.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { withNetwork { LinkSyncWorker.syncNow(context) } }, enabled = state.pairs.isNotEmpty() && progress == null) { Text("Sync now") }
                    OutlinedButton(onClick = ::loadFolders, enabled = busy == null) { Text("Choose folders") }
                }
                TextButton(onClick = { LinkSyncWorker.cancel(context); sync.store.unlink(); offered = null }) { Text("Unlink ${computer.name}") }
            }
        }
    } }
}

/**
 * "Sync your audiobook folders?": each folder the computer shares gets a phone folder (one with the same name is
 * suggested), and phone library folders can be sent to the computer. [onDone] gets the pairs and the phone folders
 * (uri to name) to create on the computer.
 */
@Composable
private fun FolderChooser(app: ReadAlongApplication, computer: String, offered: List<RemoteFolder>, current: List<FolderPair>,
    onDone: (List<FolderPair>, List<Pair<String, String>>) -> Unit, onSkip: () -> Unit) {
    val phoneFolders by app.audioImporter.folders.collectAsStateWithLifecycle(emptyList())
    val library = phoneFolders.filter { it.mode == FolderMode.LIBRARY.name }
    val shared = offered.filter { it.shared }
    // Remote folder id -> phone folder (uri, name); preselected from the current pairs, else a phone folder of the same name.
    val chosen = remember(offered, library.size) {
        mutableStateMapOf<String, Pair<String, String>>().apply {
            shared.forEach { remote ->
                current.firstOrNull { it.remoteId == remote.id }?.let { put(remote.id, it.localUri to it.localName) }
                    ?: library.firstOrNull { it.name.equals(remote.name, ignoreCase = true) }?.let { put(remote.id, it.uri to it.name) }
            }
        }
    }
    val sendHere = remember { mutableStateMapOf<String, String>() }
    var picking by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val remote = picking; picking = null
        if (uri != null && remote != null) runCatching {
            app.audioImporter.persist(uri)
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, uri)?.name ?: "Audiobooks"
            chosen[remote] = uri.toString() to name
        }
    }

    Text("$computer is linked. Choose which folders to keep the same on both: books one side doesn't have are copied to the other, " +
        "with their ReadAlongs and listening progress, whenever this phone is on Wi-Fi. Nothing is ever deleted.", style = MaterialTheme.typography.bodyLarge)
    if (shared.isEmpty()) Text("$computer isn't sharing any folders yet. On the computer, open Settings › Phone and tick the folders to sync, then come back here.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    shared.forEach { remote ->
        val local = chosen[remote.id]
        Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(local != null, { on -> if (on) { picking = remote.id; picker.launch(null) } else chosen.remove(remote.id) })
                Column(Modifier.weight(1f)) {
                    Text(remote.name, style = MaterialTheme.typography.titleMedium)
                    Text(local?.let { "On this phone: ${it.second}" } ?: "Not synced", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { picking = remote.id; picker.launch(null) }) { Text(if (local == null) "Choose folder" else "Change") }
            }
        }
    }
    val unpaired = library.filter { folder -> chosen.values.none { it.first == folder.uri } }
    if (unpaired.isNotEmpty()) {
        Text("Send these phone folders to $computer too", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        unpaired.forEach { folder ->
            Row(Modifier.fillMaxWidth().clickable { if (folder.uri in sendHere) sendHere.remove(folder.uri) else sendHere[folder.uri] = folder.name }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(folder.uri in sendHere, { on -> if (on) sendHere[folder.uri] = folder.name else sendHere.remove(folder.uri) })
                Text(folder.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text("They go in Audiobooks in your home folder on the computer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {
            val pairs = shared.mapNotNull { remote -> chosen[remote.id]?.let { (uri, name) -> FolderPair(remote.id, remote.name, uri, name) } }
            onDone(pairs, sendHere.map { it.key to it.value })
        }, enabled = chosen.isNotEmpty() || sendHere.isNotEmpty()) { Text("Sync these folders") }
        OutlinedButton(onClick = onSkip) { Text("Not now") }
    }
}
