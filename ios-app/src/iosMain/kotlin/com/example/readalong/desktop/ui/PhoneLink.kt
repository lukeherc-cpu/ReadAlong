package com.example.readalong.desktop.ui

import com.example.readalong.compat.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.readalong.data.link.RemoteFolder
import com.example.readalong.desktop.platform.QrScanner
import com.example.readalong.domain.link.FolderSyncPlan
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.dateWithTimeIntervalSince1970

/**
 * Settings' computer link on iPhone. The iPhone links the way the Android app does: ReadAlong Desktop shows a code
 * (Settings › Link a phone), the iPhone scans or pastes it, then chooses which of the computer's folders to keep in
 * sync. Books land in this iPhone's Audiobooks folder; ReadAlongs, positions and bookmarks travel both ways.
 */
@Composable
fun PhoneLinkCard(app: AppState) {
    val link = app.link
    val state by link.state.collectAsState()
    val justLinked by link.justLinked.collectAsState()
    val offered by link.offered.collectAsState()
    val activity by link.activity.collectAsState()
    val fraction by link.fraction.collectAsState()
    val requested by link.syncRequested.collectAsState()
    val scope = rememberCoroutineScope()
    var pasting by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pair(code: String) {
        linking = true; error = null
        scope.launch { error = link.pair(code); linking = false; if (error == null) pasting = false }
    }

    Surface(Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(Corners.large), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val computer = state.devices.firstOrNull()
            if (computer == null) {
                Text("Link your computer", style = MaterialTheme.typography.titleSmall)
                Text("On your computer, open ReadAlong, go to Settings › Link a phone, and scan the code it shows. Whenever both are on the same Wi-Fi " +
                    "and ReadAlong is open, books, ReadAlongs and listening progress copy across. No account or cloud: they talk directly, encrypted.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (QrScanner.available()) PillButton("Scan code", { QrScanner.scan(::pair) { error = it } }, icon = "ic_add", enabled = !linking)
                    PillButton("Paste code", { pasting = true; error = null }, filled = !QrScanner.available(), enabled = !linking)
                    if (linking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text("The first time, iOS asks to let ReadAlong find devices on your local network. Allow it, or the iPhone can't reach the computer.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(computer.name, style = MaterialTheme.typography.titleSmall)
                        Text(state.lastSyncAt?.let { "Last synced ${formatTime(it)}" } ?: "Linked, not synced yet",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        com.example.readalong.desktop.core.versionNote(System.getProperty("readalong.version"), computer.appVersion)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton({ link.unlink(computer.id) }) { Text("Unlink") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(if (requested != null || activity != null) "Syncing…" else "Sync now", { link.requestSync() }, icon = "ic_sync", filled = false,
                        enabled = requested == null && activity == null && state.pairs.isNotEmpty())
                    Text("ReadAlong also syncs by itself every few minutes while it's open on both.", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                activity?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    fraction?.let { f -> LinearProgressIndicator({ f }, Modifier.fillMaxWidth()) }
                }
                if (activity == null) state.lastResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(4.dp))
                Text("Folders synced with your computer", style = MaterialTheme.typography.labelLarge)
                if (state.pairs.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.pairs.forEach { pair ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pair.remoteName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton({ link.setShared(state.shared - pair.local) }) { Text("Stop syncing") }
                    }
                }
                PillButton("Choose folders", {
                    scope.launch { runCatching { link.remoteFolders() }.onSuccess(link::offer).onFailure { error = it.message ?: "Couldn't reach your computer." } }
                }, filled = false, icon = "ic_add")
                Spacer(Modifier.height(4.dp))
                SyncKinds(state.skipped, link::setSkipped)
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (pasting) PasteDialog(linking, { pasting = false }, ::pair)
    justLinked?.let { computer -> FolderPrompt(computer.name, offered, state.pairs.map { it.remoteId }.toSet()) { chosen -> link.choose(chosen) } }
}

private fun formatTime(millis: Long): String = NSDateFormatter().apply { dateStyle = NSDateFormatterMediumStyle; timeStyle = NSDateFormatterShortStyle }
    .stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))

@Composable
private fun PasteDialog(linking: Boolean, onCancel: () -> Unit, onLink: (String) -> Unit) {
    var code by remember { mutableStateOf(platform.UIKit.UIPasteboard.generalPasteboard.string?.trim()?.takeIf { it.startsWith("readalong-link:") }.orEmpty()) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Paste the link code") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("On your computer, in Settings › Link a phone, choose \"Copy code instead\" and send it to this iPhone (AirDrop or Notes work), then paste it here.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), placeholder = { Text("readalong-link:1?…") }, singleLine = true)
        }
    }, confirmButton = { TextButton({ onLink(code) }, enabled = code.isNotBlank() && !linking) { Text(if (linking) "Linking…" else "Link") } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } })
}

/** Asked once linked, and from "Choose folders": which of the computer's shared folders to sync. Null means "not now". */
@Composable
private fun FolderPrompt(computer: String, folders: List<RemoteFolder>, already: Set<String>, onAnswer: (List<RemoteFolder>?) -> Unit) {
    val shared = folders.filter { it.shared }
    var chosen by remember(folders) { mutableStateOf(shared.filter { it.id !in already || already.isEmpty() }.map { it.id }.toSet()) }
    AlertDialog(onDismissRequest = { onAnswer(null) }, title = { Text("Sync your audiobook folders?") }, text = {
        Column {
            Text("Linked to $computer. Choose the folders to keep in sync: whenever both are on the same Wi-Fi, this iPhone copies over the books it doesn't have " +
                "and sends back ReadAlongs and listening progress. They go in Audiobooks, which the Files app shows under On My iPhone › ReadAlong. Nothing is ever deleted.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            if (shared.isEmpty()) Text("$computer isn't sharing any folders yet. On the computer, tick the folders to sync in Settings › Link a phone, then choose Choose folders here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            shared.forEach { folder ->
                val on = folder.id in chosen
                Row(Modifier.fillMaxWidth().clickable { chosen = if (on) chosen - folder.id else chosen + folder.id }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { chosen = if (on) chosen - folder.id else chosen + folder.id })
                    Text(folder.name + if (folder.id in already) " (syncing)" else "", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }, confirmButton = {
        TextButton({ onAnswer(shared.filter { it.id in chosen }) }, enabled = chosen.isNotEmpty() || shared.isEmpty()) { Text(if (shared.isEmpty()) "OK" else "Sync these folders") }
    }, dismissButton = { TextButton({ onAnswer(null) }) { Text("Not now") } })
}

/** "What to sync": the companion files that travel with the books, all on by default. Audio always syncs. */
@Composable
private fun SyncKinds(skipped: Set<String>, onChange: (Set<String>) -> Unit) {
    Text("What to sync", style = MaterialTheme.typography.labelLarge)
    Text("Audiobooks always sync. Untick anything you'd rather keep separate on each device.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    FolderSyncPlan.checkboxes.forEach { (label, ids) ->
        val on = ids.none { it in skipped }
        Row(Modifier.fillMaxWidth().clickable { onChange(if (on) skipped + ids else skipped - ids) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, { onChange(if (on) skipped + ids else skipped - ids) })
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
