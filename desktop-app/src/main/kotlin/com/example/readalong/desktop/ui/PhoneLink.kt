package com.example.readalong.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.readalong.domain.link.FolderSyncPlan
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Settings' phone linking: linked phones, which folders they sync, and the pairing dialog. */
@Composable
fun PhoneLinkCard(app: AppState) {
    val link = app.link
    val state by link.state.collectAsState()
    val pairing by link.pairing.collectAsState()
    val justLinked by link.justLinked.collectAsState()
    val activity by link.activity.collectAsState()
    val folders by app.library.folders.collectAsState()
    // Leaving Settings stops waiting for a phone.
    DisposableEffect(Unit) { onDispose { if (link.pairing.value != null) link.cancelPairing() } }

    Surface(Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(Corners.large), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp)) {
            if (state.devices.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Link your phone", style = MaterialTheme.typography.titleSmall)
                        Text("Scan a code with ReadAlong on your phone, then choose audiobook folders to keep in sync. Whenever both are on the same Wi-Fi, " +
                            "books, ReadAlongs and listening progress copy across. No account or cloud: they talk directly, encrypted.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(16.dp))
                    PillButton("Link phone", { link.startPairing() }, icon = "ic_add")
                }
            } else {
                state.devices.forEach { device ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text(device.lastSeen?.let { "Last synced ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}" } ?: "Linked, not synced yet",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            com.example.readalong.desktop.core.versionNote(device.appVersion, System.getProperty("readalong.version"))?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton({ link.unlink(device.id) }) { Text("Unlink") }
                    }
                }
                activity?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.height(12.dp))
                Text("Folders synced with your phone", style = MaterialTheme.typography.labelLarge)
                if (folders.isEmpty()) Text("Add a library folder first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                folders.forEach { folder ->
                    val on = folder in state.shared
                    Row(Modifier.fillMaxWidth().clickable { link.setShared(if (on) state.shared - folder else state.shared + folder) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { link.setShared(if (on) state.shared - folder else state.shared + folder) })
                        Text(folder, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
                SyncKinds(state.skipped, link::setSkipped)
                Spacer(Modifier.height(8.dp))
                PillButton("Link another phone", { link.startPairing() }, filled = false, icon = "ic_add")
            }
        }
    }

    val code = pairing
    if (code != null && justLinked == null) PairingDialog(code.encode()) { link.cancelPairing() }
    justLinked?.let { phone -> FolderPrompt(phone.name, folders, state.skipped, link::setSkipped) { chosen -> chosen?.let(link::setShared); link.folderPromptAnswered() } }
}

@Composable
private fun PairingDialog(code: String, onCancel: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Link your phone") }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("On your phone, open ReadAlong, go to Settings › Link a computer, and scan this code. Keep both on the same Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            QrCode(code, Modifier.size(260.dp))
            Spacer(Modifier.height(12.dp))
            Text("If Windows or macOS asks, allow ReadAlong on private networks so your phone can reach it.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onCancel) { Text("Cancel") } }, dismissButton = {
        TextButton({
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(code), null); copied = true
        }) { Text(if (copied) "Copied" else "Copy code instead") }
    })
}

/** Asked once a phone has linked. Null means "not now". */
@Composable
private fun FolderPrompt(phone: String, folders: List<String>, skipped: Set<String>, onSkipped: (Set<String>) -> Unit, onAnswer: (List<String>?) -> Unit) {
    var chosen by remember { mutableStateOf(folders.toSet()) }
    AlertDialog(onDismissRequest = { onAnswer(null) }, title = { Text("Sync your audiobook folders?") }, text = {
        Column {
            Text("$phone is linked. Choose the folders to keep in sync: whenever both are on the same Wi-Fi, your phone copies over the books it doesn't have " +
                "and sends back its own, with ReadAlongs and listening progress. Nothing is ever deleted.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            if (folders.isEmpty()) Text("You have no library folders yet. Your phone can still send its books here: they go in Audiobooks in your home folder.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            folders.forEach { folder ->
                Row(Modifier.fillMaxWidth().clickable { chosen = if (folder in chosen) chosen - folder else chosen + folder }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(folder in chosen, { chosen = if (folder in chosen) chosen - folder else chosen + folder })
                    Column {
                        Text(File(folder).name, style = MaterialTheme.typography.bodyMedium)
                        Text(folder, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SyncKinds(skipped, onSkipped)
        }
    }, confirmButton = { TextButton({ onAnswer(chosen.toList()) }, enabled = chosen.isNotEmpty() || folders.isEmpty()) { Text(if (folders.isEmpty()) "OK" else "Sync these folders") } },
        dismissButton = { TextButton({ onAnswer(null) }) { Text("Not now") } })
}

@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    // Always dark on white, whatever the theme: phone scanners expect it.
    Canvas(modifier.background(Color.White, RoundedCornerShape(Corners.medium))) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix.get(x, y))
            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}

/** "What to sync": the companion files that travel with the books, all on by default. Audio always syncs. */
@Composable
private fun SyncKinds(skipped: Set<String>, onChange: (Set<String>) -> Unit) {
    Text("What to sync", style = MaterialTheme.typography.labelLarge)
    Text("Audiobooks always sync. Untick anything you'd rather keep separate on each device.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    listOf(FolderSyncPlan.Kind.TRANSCRIPT to "ReadAlong transcripts", FolderSyncPlan.Kind.PROGRESS to "Listening progress",
        FolderSyncPlan.Kind.EPUB to "EPUBs", FolderSyncPlan.Kind.COVER to "Cover art").forEach { (kind, label) ->
        val on = kind.id !in skipped
        Row(Modifier.fillMaxWidth().clickable { onChange(if (on) skipped + kind.id else skipped - kind.id) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, { onChange(if (on) skipped + kind.id else skipped - kind.id) })
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
