package com.example.readalong.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.readalong.desktop.core.UpdateStatus
import com.example.readalong.domain.update.AppRelease
import com.example.readalong.domain.update.UpdateCheck
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** "Update available" after the daily check. Downloading opens the installer for this computer in the browser; the Flatpak updates through flatpak instead. */
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
        Text("ReadAlong ${release.version} is out. You have ${app.updates.current}.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // What changed, from the release's notes.
        UpdateCheck.summary(release.notes, maxLines = 5).takeIf { it.isNotBlank() }?.let { notes ->
            Text(notes, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (app.updates.flatpak) {
            Text("Update ReadAlong from your software centre, or run this in a terminal. Your library stays as it is.",
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(app.updates.flatpakUpdateCommand, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            }
        }
        PillButton(if (app.updates.flatpak) "Copy command" else "Download", {
            getUpdate(app, release)
            app.updates.later()
        }, Modifier.fillMaxWidth().height(48.dp))
        PillButton("Later", app.updates::later, Modifier.fillMaxWidth().height(48.dp), filled = false)
        // Not offered again automatically until a newer version is out; Check for updates still shows it.
        TextButton({ app.updates.skip(release) }) { Text("Skip ${release.version}") }
    }
}

/** Opens the installer download, or in the Flatpak copies the `flatpak update` command, which is how it updates. */
fun getUpdate(app: AppState, release: AppRelease) {
    if (app.updates.flatpak) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(app.updates.flatpakUpdateCommand), null) }
            .onSuccess { app.messages.tryEmit("Copied. Paste it into a terminal, or update ReadAlong from your software centre.") }
            .onFailure { app.messages.tryEmit("Run ${app.updates.flatpakUpdateCommand}, or update ReadAlong from your software centre.") }
    } else if (!openBrowser(app.updates.downloadUrl(release))) app.messages.tryEmit("Open ${release.pageUrl} to download the update.")
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
                    is UpdateStatus.Available -> TextButton({ getUpdate(app, s.release) }) {
                        Text(if (app.updates.flatpak) "Copy the update command for ${s.release.version}" else "Download ReadAlong ${s.release.version}")
                    }
                    is UpdateStatus.Failed -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    else -> {}
                }
            }
        }
    }
}
