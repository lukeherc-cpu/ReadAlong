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

/** "Update available" after the daily check. Downloading opens the installer for this computer in the browser. */
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

/** Styled like the app's other prompts: the ReadAlong logo, a short title and two full-width pill buttons in the current theme. */
@Composable
private fun UpdateBody(app: AppState, release: AppRelease) {
    Column(Modifier.width(380.dp).padding(horizontal = 28.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(AppIcons.get("ic_readalong"), null, Modifier.size(72.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
        Text("Update available", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        if (app.updates.flatpak) {
            Text("ReadAlong ${release.version} is out. Update it from your software center, or run flatpak update.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PillButton("OK", app.updates::later, Modifier.fillMaxWidth().height(48.dp))
        } else {
            PillButton("Download", {
                if (!openBrowser(app.updates.downloadUrl(release))) app.messages.tryEmit("Open ${release.pageUrl} to download the update.")
                app.updates.later()
            }, Modifier.fillMaxWidth().height(48.dp))
            PillButton("Later", app.updates::later, Modifier.fillMaxWidth().height(48.dp), filled = false)
        }
        // Not offered again automatically until a newer version is out; Check for updates still shows it.
        TextButton({ app.updates.skip(release) }) { Text("Skip ${release.version}") }
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
                    is UpdateStatus.Available -> if (app.updates.flatpak) Text("ReadAlong ${s.release.version} is out: update it from your software center.")
                        else TextButton({ openBrowser(app.updates.downloadUrl(s.release)) }) { Text("Download ReadAlong ${s.release.version}") }
                    is UpdateStatus.Failed -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    else -> {}
                }
            }
        }
    }
}
