@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.readalong.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.readalong.desktop.core.AppPaths
import com.example.readalong.desktop.core.UpdateStatus
import com.example.readalong.desktop.transcription.SpeechModels
import kotlinx.coroutines.launch

/**
 * Settings, laid out like the phone's: the tip jar, Appearance, Playback, Transcription (ReadAlong), Other and
 * Advanced. Where to transcribe, the language and the speech model sit behind the Transcription (ReadAlong) row.
 */
@Composable
fun SettingsScreen(app: AppState) {
    val settings by app.settingsStore.settings.collectAsState()
    val devices by app.transcription.devices.collectAsState()
    val linkState by app.link.state.collectAsState()
    val updateStatus by app.updates.status.collectAsState()
    val scope = rememberCoroutineScope()
    fun save(change: (com.example.readalong.desktop.core.AppSettings) -> com.example.readalong.desktop.core.AppSettings) = app.settingsStore.update(change)
    SettingsPage("Settings", "Back (Esc)", app) {
        if (!settings.tipJarInOther) TipJarSection(false) { move -> save { it.copy(tipJarInOther = move) } }

        SectionLabel("Appearance")
        SettingsGroup {
            PreferenceSwitch("Light theme", settings.light, "ic_theme") { v -> save { it.copy(light = v) } }
            SettingsRow("Accent color", "ic_palette", onClick = null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentPalettes.forEach { p ->
                        val chosen = settings.accent == p.color
                        Box(Modifier.size(26.dp).clip(CircleShape).background(Color(p.color)).clickable { save { it.copy(accent = p.color) } }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .then(if (chosen) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier))
                    }
                }
            }
            PreferenceSwitch("Match mini player to book colors", settings.coloredMiniPlayer, "ic_play") { v -> save { it.copy(coloredMiniPlayer = v) } }
            PreferenceSwitch("Use accent color instead of cover colors", settings.alwaysAccent, "ic_image") { v -> save { it.copy(alwaysAccent = v) } }
            PreferenceSwitch("Show title and author", settings.showTitleAndAuthor, "ic_book") { v -> save { it.copy(showTitleAndAuthor = v) } }
            AppFontRow(settings.appFont) { font -> save { it.copy(appFont = font) } }
        }

        SectionLabel("Playback")
        SettingsGroup {
            Stepper("Rewind", "ic_rewind", settings.skipBackSeconds) { v -> save { it.copy(skipBackSeconds = v) } }
            Stepper("Forward", "ic_forward", settings.skipForwardSeconds) { v -> save { it.copy(skipForwardSeconds = v) } }
            Hint("Keyboard: Space plays and pauses, ← and → skip, Esc goes back, ${AppPaths.shortcutKey}F searches the text.")
        }

        SectionLabel("Transcription (ReadAlong)")
        SettingsGroup {
            val gpu = devices.orEmpty().any { it.isGpu } && settings.device != "cpu"
            SettingsRow("Transcription (ReadAlong)", "ic_waveform",
                summary = "${app.transcription.model().name} · ${if (gpu) "Graphics card" else "Processor"} · ${languageName(settings.language)}" +
                    if (settings.aggressive) " · Aggressive" else "",
                onClick = { app.open(Screen.TranscriptionSettings) })
        }

        SectionLabel("Other")
        SettingsGroup {
            SettingsRow("Link a phone", "ic_devices", summary = linkSummary(linkState), onClick = { app.open(Screen.PhoneSettings) })
            SettingsRow("Library folders", "ic_folder", onClick = { app.open(Screen.Folders) })
            PreferenceSwitch("Check for updates automatically", settings.checkUpdates, "ic_redo") { v -> save { it.copy(checkUpdates = v) } }
            SettingsRow(when (updateStatus) { UpdateStatus.Checking -> "Checking…"; else -> "Check for updates now" }, "ic_info",
                summary = when (val s = updateStatus) {
                    UpdateStatus.UpToDate -> "ReadAlong ${app.updates.current} is the latest version."
                    is UpdateStatus.Available -> "ReadAlong ${s.release.version} is out. Click to download it."
                    is UpdateStatus.Failed -> s.message
                    else -> "Version ${System.getProperty("readalong.version") ?: "dev"}"
                },
                onClick = {
                    when (val s = updateStatus) {
                        is UpdateStatus.Available -> openBrowser(app.updates.downloadUrl(s.release))
                        UpdateStatus.Checking -> {}
                        else -> scope.launch { app.updates.check(manual = true) }
                    }
                })
        }

        SectionLabel("Advanced")
        SettingsGroup {
            SettingsRow("Copy crash details for support", "ic_bug",
                summary = "This computer's details, the books that stopped and why, and the latest crash logs. Never your books' text.",
                onClick = {
                    scope.launch {
                        val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { crashDetails(app) }
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(report), null)
                        app.messages.emit("Crash details copied.")
                    }
                }, trailing = {})
            SettingsRow("Open data folder", "ic_folder", summary = AppPaths.data.path,
                onClick = { runCatching { java.awt.Desktop.getDesktop().open(AppPaths.data) } }, trailing = {})
        }

        if (settings.tipJarInOther) TipJarSection(true) { move -> save { it.copy(tipJarInOther = move) } }
        Text("ReadAlong for ${AppPaths.platformName} ${System.getProperty("readalong.version") ?: "dev"}", Modifier.fillMaxWidth().padding(top = 8.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Settings → Transcription (ReadAlong): where to run, how hard, the language and the speech model. */
@Composable
fun TranscriptionSettingsScreen(app: AppState) {
    val settings by app.settingsStore.settings.collectAsState()
    val devices by app.transcription.devices.collectAsState()
    val installed by app.models.installed.collectAsState()
    val downloads by app.models.downloads.collectAsState()
    val scope = rememberCoroutineScope()
    SettingsPage("Transcription (ReadAlong)", "Back to Settings (Esc)", app) {
        val gpus = devices.orEmpty().filter { it.isGpu }
        SettingsGroup {
            Column(Modifier.padding(18.dp)) {
                Text(when {
                    devices == null -> "Checking this computer's graphics card…"
                    gpus.isEmpty() -> "No graphics card that ReadAlong can use was found, so it transcribes on the processor. Updating the graphics driver can help."
                    else -> "Graphics card: " + gpus.joinToString { "${it.description.ifBlank { it.name }} (${it.backend}${if (it.memoryTotal > 0) ", ${it.memoryTotal / (1L shl 30)} GB" else ""})" }
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Text("Run on", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "Graphics card if available", "cpu" to "Processor only").forEach { (key, label) ->
                        FilterChip(settings.device == key || (key == "auto" && settings.device == "gpu"), { app.settingsStore.update { it.copy(device = key) } }, { Text(label) })
                    }
                }
            }
            PreferenceSwitch("Aggressive transcription", settings.aggressive, "ic_speed") { v -> app.settingsStore.update { it.copy(aggressive = v) } }
            Hint("Uses the graphics card and the processor together, several parts of the book at once, with quicker decoding. Books finish several times sooner; the fans will spin up while it works.")
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
                Text("Language of your audiobooks", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Languages.forEach { (code, label) ->
                        FilterChip(settings.language == code, { app.settingsStore.update { it.copy(language = code) } }, { Text(label) })
                    }
                }
            }
        }

        SectionLabel("Speech model")
        val default = app.transcription.defaultModel()
        val chosen = app.transcription.model()
        SpeechModels.all.filter { settings.language == "en" || !it.englishOnly }.forEach { model ->
            val progress = downloads[model.id]
            val selected = chosen.id == model.id
            Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(Corners.large))
                .clickable { app.settingsStore.update { it.copy(modelId = model.id) } }.pointerHoverIcon(PointerIcon.Hand),
                shape = RoundedCornerShape(Corners.large),
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .6f)) else null) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected, { app.settingsStore.update { it.copy(modelId = model.id) } })
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(model.name, style = MaterialTheme.typography.titleSmall)
                            if (model.id == default.id) {
                                Spacer(Modifier.width(8.dp))
                                Text("Default", Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                        Text(model.summary + " ${model.sizeBytes / 1_000_000} MB.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (progress != null) { Spacer(Modifier.height(8.dp)); ProgressLine(progress) }
                    }
                    when {
                        progress != null -> Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                        // The default downloads by itself and is kept, so the first book never waits on a manual step.
                        model.id == default.id -> Text(if (model.id in installed) "Ready" else "Downloading soon", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                        model.id in installed -> TextButton({ app.models.delete(model) }) { Text("Delete") }
                        else -> TextButton({ scope.launch { runCatching { app.models.download(model) }.onFailure { app.messages.emit(it.message ?: "Download failed.") } } }) { Text("Download") }
                    }
                }
            }
        }
        Text("${default.name} downloads by itself, so your first book can start straight away. The others download from Hugging Face the first time they're used, then work offline.",
            Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Settings → Link a phone: the shared transcripts beside each book, and pairing over the home network. */
@Composable
fun PhoneSettingsScreen(app: AppState) {
    val settings by app.settingsStore.settings.collectAsState()
    SettingsPage("Link a phone", "Back to Settings (Esc)", app) {
        SettingsGroup {
            PreferenceSwitch("Save each ReadAlong beside its audiobook", settings.savePackages, "ic_book") { v -> app.settingsStore.update { it.copy(savePackages = v) } }
            Hint("Writes <book>.readalong next to the audio files, in the format the phone app imports. When the same folder is on your phone, " +
                "its library scan picks the transcript up, so the phone doesn't need to transcribe the book again.")
        }
        PhoneLinkCard(app)
    }
}

/** The tip jar, as on the phone: a card that opens Ko-fi, with the box that moves it to the bottom of Settings. */
@Composable
private fun TipJarSection(inOther: Boolean, onMove: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(Modifier.fillMaxWidth().padding(top = if (inOther) 14.dp else 0.dp)) {
        Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).hoverable(interaction)
            .clickable(interaction, null) { openBrowser(DONATE_URL) }.pointerHoverIcon(PointerIcon.Hand),
            shape = RoundedCornerShape(24.dp), color = colors.surface) {
            Row(Modifier.background(Brush.horizontalGradient(listOf(colors.primaryContainer.copy(alpha = if (hovered) .8f else .65f), colors.primaryContainer.copy(alpha = .38f))))
                .padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(AppIcons.get("ic_tip_jar"), null, Modifier.size(width = 48.dp, height = 60.dp), tint = colors.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Tip jar", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                    Text("Support the development of ReadAlong", style = MaterialTheme.typography.bodySmall, color = colors.primary)
                }
                Icon(AppIcons.get("ic_chevron_right"), null, Modifier.size(24.dp), tint = colors.onSurface)
            }
        }
        Row(Modifier.toggleable(inOther, role = Role.Checkbox, onValueChange = onMove).pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(inOther, null)
            Text("Move tip jar to the bottom of Settings", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Settings → App font: a menu of the bundled fonts, each shown in itself. */
@Composable
private fun AppFontRow(value: String, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = Fonts.reading.firstOrNull { it.first == value }?.second ?: "Plus Jakarta Sans"
    Box {
        SettingsRow("App font", "ic_font", onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, fontFamily = Fonts.reading(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Chevron()
            }
        }
        DropdownMenu(open, { open = false }, Modifier.align(Alignment.TopEnd)) {
            Fonts.reading.forEach { (key, label) ->
                DropdownMenuItem({ Text(label, fontFamily = Fonts.reading(key)) }, { onChoose(key); open = false },
                    trailingIcon = { if (key == value) Icon(androidx.compose.material.icons.Icons.Rounded.Check, "Selected", Modifier.size(18.dp)) })
            }
        }
    }
}

/** A settings page: back button, title, and a scrolling column the width of the window. */
@Composable
private fun SettingsPage(title: String, back: String, app: AppState, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().smoothWheelScroll(scroll).verticalScroll(scroll).padding(horizontal = 28.dp)) {
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIconButton("ic_back", back, { app.back() })
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.headlineMedium)
            }
            // The page's full width, like the phone's Settings.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            Spacer(Modifier.height(40.dp))
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

private val Languages = listOf("en" to "English", "auto" to "Detect", "de" to "German", "fr" to "French", "es" to "Spanish", "it" to "Italian", "nl" to "Dutch", "pl" to "Polish")
private fun languageName(code: String) = Languages.firstOrNull { it.first == code }?.second ?: code

private fun linkSummary(state: com.example.readalong.desktop.link.LinkState): String = when (state.devices.size) {
    0 -> "Sync transcripts, positions and bookmarks with ReadAlong on your phone"
    1 -> "Linked to ${state.devices[0].name}"
    else -> "Linked to ${state.devices.size} devices"
}

// Quiet, sentence-case headings so the rows, not the labels, carry the page (as on the phone).
@Composable private fun SectionLabel(value: String) {
    Text(value, Modifier.padding(top = 14.dp, start = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(Corners.large), color = MaterialTheme.colorScheme.surfaceContainer) { Column(Modifier.fillMaxWidth(), content = content) }
}

@Composable private fun Hint(text: String) {
    Text(text, Modifier.padding(start = 58.dp, end = 18.dp).padding(bottom = 14.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun RowIcon(icon: String) = Icon(AppIcons.get(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable private fun Chevron() = Icon(AppIcons.get("ic_chevron_right"), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)

/** A row with an icon and a label; clickable rows end in a chevron, like the phone's. */
@Composable private fun SettingsRow(label: String, icon: String, summary: String? = null, onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit = { if (onClick != null) Chevron() }) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (onClick != null) Modifier.hoverable(interaction).clickable(interaction, null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier)
        .background(if (hovered) MaterialTheme.colorScheme.onSurface.copy(alpha = .05f) else Color.Transparent)
        .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RowIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing()
    }
}

/** The whole row toggles, with a switch on every on/off setting (no checkboxes), as on the phone. */
@Composable private fun PreferenceSwitch(label: String, checked: Boolean, icon: String, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = change).pointerHoverIcon(PointerIcon.Hand)
        .padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RowIcon(icon)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, null)
    }
}

@Composable private fun Stepper(label: String, icon: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RowIcon(icon)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton({ onChange((value - 5).coerceAtLeast(5)) }) { Text("−") }
        Text("$value s", Modifier.width(56.dp), textAlign = TextAlign.Center)
        OutlinedButton({ onChange((value + 5).coerceAtMost(120)) }) { Text("+") }
    }
}

/** Settings → Copy crash details. */
private fun crashDetails(app: AppState): String {
    val settings = app.settingsStore.settings.value
    val model = app.transcription.model()
    return com.example.readalong.desktop.core.CrashReport.build(
        devices = app.transcription.devices.value.orEmpty().map { "${it.type} ${it.label}${if (it.memoryTotal > 0) " ${it.memoryTotal / (1L shl 20)} MB" else ""}" },
        settings = listOf("Model: ${model.id} · run on ${settings.device} · aggressive ${settings.aggressive} · language ${settings.language}"),
        bookErrors = app.transcription.errors.value.map { (id, error) -> (app.library.book(id)?.title ?: id) to error },
    )
}
