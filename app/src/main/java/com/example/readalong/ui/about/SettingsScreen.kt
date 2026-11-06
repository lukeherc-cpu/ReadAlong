package com.example.readalong.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.ReadAlongApplication
import com.example.readalong.R
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: ReadAlongApplication, onBack: () -> Unit, onModel: () -> Unit, onAbout: () -> Unit, onLink: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val preferences by app.appPreferences.preferences.collectAsStateWithLifecycle(com.example.readalong.data.preferences.AppPreferences())
    val reader by app.readerPreferences.preferences.collectAsStateWithLifecycle(com.example.readalong.data.preferences.ReaderPreferences())
    var appFont by remember { mutableStateOf(false) }
    var appearance by remember { mutableStateOf(false) }
    var skipDialog by remember { mutableStateOf(false) }
    var colorPicker by remember { mutableStateOf(false) }
    var pendingTheme by remember { mutableStateOf<Boolean?>(null) }
    var pendingColor by remember { mutableStateOf<Long?>(null) }
    var backgroundPicker by remember { mutableStateOf(false) }
    fun save(change: (com.example.readalong.data.preferences.AppPreferences) -> com.example.readalong.data.preferences.AppPreferences) { scope.launch { app.appPreferences.update(change) } }
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    var copied by remember { mutableStateOf(false) }
    var battery by remember { mutableStateOf(false) }
    Scaffold { insets -> Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back to library") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }
        // Grouped by where a setting shows up, so no group mixes the app's look with the player or the reader.
        SettingsSection("Look") {
            SettingsGroup {
                PreferenceSwitch("Light theme", preferences.libraryLight, "Light theme") { value -> pendingTheme = value }
                SettingsRow("Accent color", onClick = { colorPicker = true }) { Box(Modifier.size(24.dp).background(androidx.compose.ui.graphics.Color(preferences.basicColor), RoundedCornerShape(com.example.readalong.ui.theme.Corners.small))) }
                PreferenceSwitch("Use accent color instead of cover colors", preferences.alwaysBasicColor) { value -> save { it.copy(alwaysBasicColor = value) } }
                PreferenceSwitch("Match mini player to book colors", preferences.coloredMiniPlayer) { value -> save { it.copy(coloredMiniPlayer = value) } }
                SettingsRow("App font", onClick = { appFont = true })
                PreferenceSwitch("Animate the queue logo while working", preferences.animateTranscriptionLogo) { value -> save { it.copy(animateTranscriptionLogo = value) } }
            }
        }
        SettingsSection("Library") {
            SettingsGroup {
                PreferenceSwitch("Show title and author", preferences.showTitleAndAuthor) { value -> save { it.copy(showTitleAndAuthor = value) } }
                PreferenceSwitch("Progress bar on covers", preferences.coverProgressBar) { value -> save { it.copy(coverProgressBar = value) } }
            }
        }
        SettingsSection("Listening and reading") {
            Text("Open books to", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OpeningChoices(preferences.openingView) { view -> save { it.copy(openingView = view) } }
            SettingsGroup {
                if (preferences.openingView in setOf("PREVIEW", "FULLSCREEN"))
                    PreferenceSwitch("Open the text in full screen", preferences.openingView == "FULLSCREEN") { value -> save { it.copy(openingView = if (value) "FULLSCREEN" else "PREVIEW") } }
                SettingsRow("Player background", onClick = { backgroundPicker = true }) { ValueText(if (preferences.blurredPlayer) "Blurred cover" else "Solid color"); Chevron() }
                SettingsRow("Reading fonts and appearance", onClick = { appearance = true })
                SettingsRow("Rewind and forward times", onClick = { skipDialog = true }) { ValueText("${preferences.playerBack}s · ${preferences.playerForward}s"); Chevron() }
            }
        }
        SettingsSection("Transcription") {
            SettingsGroup {
                PreferenceSwitch("Only while charging", preferences.chargingOnly, summary = "Pauses when you unplug and picks up again when you charge") { value -> scope.launch { app.appPreferences.update { it.copy(chargingOnly = value) }; app.processing.chargingPreferenceChanged() } }
                PreferenceSwitch("Fast transcription", preferences.aggressiveTranscription, summary = "Keeps going when the phone is warm or on battery saver. Uses more battery, and still pauses if the phone gets hot") { value -> save { it.copy(aggressiveTranscription = value) } }
                SettingsRow("Offline speech models", onClick = onModel)
                SettingsRow("Background work and battery", onClick = { battery = true })
            }
        }
        SettingsSection("Devices") {
            SettingsGroup { SettingsRow("Link a computer", onClick = onLink) }
        }
        SettingsSection("About") {
            SettingsGroup {
                UpdateSettings()
                SettingsRow("About ReadAlong", onClick = onAbout)
                TipJarRow()
                SettingsRow(if (copied) "Crash details copied" else "Copy crash details for support", onClick = { scope.launch {
                    val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.example.readalong.data.diagnostics.CrashDiagnostics.read(app) + com.example.readalong.data.diagnostics.CrashDiagnostics.transcriptionFailures(app.database)
                    }
                    clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("ReadAlong crash details", report))); copied = true
                } })
            }
        }
    } }
    if (appFont) com.example.readalong.ui.reading.AppFontDialog(preferences.appFont, { appFont = false }) { font -> save { it.copy(appFont = font) }; appFont = false }
    if (appearance) com.example.readalong.ui.reading.ReaderAppearance(reader, { appearance = false }) { value -> scope.launch { app.readerPreferences.save(value) }; appearance = false }
    if (colorPicker) BasicColorPicker(preferences.basicColor, { colorPicker = false }) { color -> pendingColor = color; colorPicker = false }
    // Theme and color apply at once; the app icon and launch screen follow when you leave ReadAlong.
    LaunchedEffect(pendingTheme, pendingColor) {
        val light = pendingTheme; val color = pendingColor
        if (light == null && color == null) return@LaunchedEffect
        pendingTheme = null; pendingColor = null
        save { it.copy(libraryLight = light ?: it.libraryLight, basicColor = color ?: it.basicColor) }
    }
    if (backgroundPicker) AlertDialog(onDismissRequest = { backgroundPicker = false }, title = { Text("Player background") }, text = {
        Column { listOf(true to "Blurred cover art", false to "Solid color").forEach { (blur, label) ->
            Row(Modifier.fillMaxWidth().clickable { save { it.copy(blurredPlayer = blur) }; backgroundPicker = false }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(preferences.blurredPlayer == blur, onClick = { save { it.copy(blurredPlayer = blur) }; backgroundPicker = false }); Text(label)
            }
        } }
    }, confirmButton = { TextButton(onClick = { backgroundPicker = false }) { Text("Close") } })
    if (skipDialog) {
        var back by remember { mutableStateOf(preferences.playerBack.toString()) }; var forward by remember { mutableStateOf(preferences.playerForward.toString()) }
        var readerBack by remember { mutableStateOf(preferences.readerBack.toString()) }; var readerForward by remember { mutableStateOf(preferences.readerForward.toString()) }
        val valid = listOf(back, forward, readerBack, readerForward).all { (it.toIntOrNull() ?: 0) in 1..600 }
        // One back/forward pair; full-screen reading has its own (5 s by default) behind an expander.
        var readerTimes by remember { mutableStateOf(preferences.readerBack != 5 || preferences.readerForward != 5) }
        AlertDialog(onDismissRequest = { skipDialog = false }, title = { Text("Rewind and forward") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How far the rewind and forward buttons jump, in seconds (1 to 600).")
                SecondsField("Rewind", back) { back = it }; SecondsField("Forward", forward) { forward = it }
                TextButton(onClick = { readerTimes = !readerTimes }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (readerTimes) "Full-screen reading times" else "Change full-screen reading times")
                }
                if (readerTimes) { SecondsField("Full-screen rewind", readerBack) { readerBack = it }; SecondsField("Full-screen forward", readerForward) { readerForward = it } }
            }
        }, confirmButton = { TextButton(enabled = valid, onClick = {
            save { it.copy(playerBack = back.toInt(), playerForward = forward.toInt(), readerBack = readerBack.toInt(), readerForward = readerForward.toInt()) }; skipDialog = false
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { skipDialog = false }) { Text("Cancel") } })
    }
    if (battery) com.example.readalong.ui.transcription.BackgroundPreparationPrompt(app, force = true, onClose = { battery = false })
}

// Quiet, sentence-case headings so the rows, not the labels, carry the page.
@Composable private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
@Composable private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = MaterialTheme.colorScheme.surfaceContainer) { Column(Modifier.fillMaxWidth(), content = content) }
}
@Composable internal fun Chevron() = Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
/** A row's current value, shown before its chevron. */
@Composable internal fun ValueText(value: String) = Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
@Composable private fun RowLabel(label: String, summary: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
/** A tappable settings row; also used by the flavour-specific update and tip jar rows. */
@Composable internal fun SettingsRow(label: String, modifier: Modifier = Modifier, summary: String? = null, enabled: Boolean = true, onClick: () -> Unit, trailing: @Composable () -> Unit = { Chevron() }) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RowLabel(label, summary, Modifier.weight(1f)); trailing()
    }
}
@Composable internal fun PreferenceSwitch(label: String, checked: Boolean, description: String = label, summary: String? = null, modifier: Modifier = Modifier, change: (Boolean) -> Unit) {
    // The whole row toggles, with a switch on every on/off setting (no checkboxes).
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = change)
        .semantics { contentDescription = description }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RowLabel(label, summary, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}
/** Where a book opens: one tap on a three-part control (the full-screen switch below refines "ReadAlong"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun OpeningChoices(openingView: String, choose: (String) -> Unit) {
    val options = listOf(Triple("COVER", "Cover", "Cover art"), Triple("PREVIEW", "ReadAlong", "ReadAlong text"), Triple("READ_ONLY", "Read only", "Read only (no audio)"))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (view, label, description) ->
            val selected = if (view == "PREVIEW") openingView in setOf("PREVIEW", "FULLSCREEN") else openingView == view
            SegmentedButton(selected = selected, onClick = { if (!selected) choose(view) }, shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.semantics { contentDescription = description }) { Text(label, maxLines = 1) }
        }
    }
}
@Composable private fun SecondsField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, { change(it.filter(Char::isDigit).take(3)) }, label = { Text(label) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
}
