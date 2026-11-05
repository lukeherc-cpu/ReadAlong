package com.example.readalong.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.readalong.desktop.core.UpdateStatus
import com.example.readalong.domain.update.AppRelease
import com.example.readalong.domain.update.UpdateCheck
import kotlinx.coroutines.launch

/** "ReadAlong X is available" after the daily check. Downloading opens the installer for this computer in the browser. */
@Composable
fun UpdateDialog(app: AppState) {
    val release by app.updates.offer.collectAsState()
    val offered = release ?: return
    Dialog(onDismissRequest = app.updates::later) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 16.dp) {
            UpdateBody(app, offered)
        }
    }
}

@Composable
private fun UpdateBody(app: AppState, release: AppRelease) {
    Column(Modifier.width(480.dp).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("ReadAlong ${release.version} is available", style = MaterialTheme.typography.headlineSmall)
        Text("You have ${app.updates.current}. Your library, ReadAlongs and listening progress stay as they are, and the phone keeps syncing with either version.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val notes = UpdateCheck.summary(release.notes, maxLines = 8)
        if (notes.isNotBlank()) Surface(shape = RoundedCornerShape(Corners.medium), color = MaterialTheme.colorScheme.surfaceContainer) {
            Text(notes, Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(14.dp), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ app.updates.skip(release) }) { Text("Skip this version") }
            Spacer(Modifier.weight(1f))
            PillButton("Later", app.updates::later, filled = false)
            PillButton("Download", {
                if (!openBrowser(app.updates.downloadUrl(release))) app.messages.tryEmit("Open ${release.pageUrl} to download the update.")
                app.updates.later()
            })
        }
    }
}

/** The Updates card in Settings: automatic checks, a manual check and what it found. */
@Composable
fun UpdatesSettings(app: AppState) {
    val settings by app.settingsStore.settings.collectAsState()
    val status by app.updates.status.collectAsState()
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(Corners.large), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check for updates automatically", style = MaterialTheme.typography.titleSmall)
                    Text("Once a day, ReadAlong asks GitHub whether a newer version is out. Nothing downloads until you choose to.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(settings.checkUpdates, { v -> app.settingsStore.update { it.copy(checkUpdates = v) } })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (status == UpdateStatus.Checking) "Checking…" else "Check for updates", { scope.launch { app.updates.check(manual = true) } },
                    filled = false, enabled = status != UpdateStatus.Checking)
                when (val s = status) {
                    UpdateStatus.UpToDate -> Text("ReadAlong ${app.updates.current} is the latest version.", style = MaterialTheme.typography.bodyMedium)
                    is UpdateStatus.Available -> TextButton({ openBrowser(app.updates.downloadUrl(s.release)) }) { Text("Download ReadAlong ${s.release.version}") }
                    is UpdateStatus.Failed -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    else -> {}
                }
            }
        }
    }
}
