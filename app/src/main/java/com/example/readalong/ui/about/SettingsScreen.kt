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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.ReadAlongApplication
import com.example.readalong.R
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: ReadAlongApplication, onBack: () -> Unit, onModel: () -> Unit, onAbout: () -> Unit) {
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
    Scaffold { insets -> Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back to library") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }
        if (!preferences.tipJarInOther) TipJarSection(false) { move -> save { it.copy(tipJarInOther = move) } }
        SectionLabel("Appearance")
        SettingsGroup {
            PreferenceSwitch("Theme", preferences.libraryLight, "Light library", valueLabel = if (preferences.libraryLight) "Light" else "Dark") { value -> pendingTheme = value }
            PreferenceSwitch("Match mini-player to book colors", preferences.coloredMiniPlayer) { value -> save { it.copy(coloredMiniPlayer = value) } }
            SettingsRow("Accent color", onClick = { colorPicker = true }) { Box(Modifier.size(24.dp).background(androidx.compose.ui.graphics.Color(preferences.basicColor), RoundedCornerShape(8.dp))) }
            PreferenceSwitch("Use accent color instead of cover colors", preferences.alwaysBasicColor) { value -> save { it.copy(alwaysBasicColor = value) } }
            SettingsRow("Player background", onClick = { backgroundPicker = true }) { Text(if (preferences.blurredPlayer) "Blurred cover art" else "Solid color", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            PreferenceCheckbox("Show title and author", preferences.showTitleAndAuthor) { value -> save { it.copy(showTitleAndAuthor = value) } }
            SettingsRow("App font", onClick = { appFont = true })
            SettingsRow("Reading fonts and appearance", onClick = { appearance = true })
        }
        SectionLabel("When opening a book")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OpeningChoice("Cover art", "Cover art", R.drawable.ic_image, preferences.openingView == "COVER", Modifier.weight(1f)) { save { it.copy(openingView = "COVER") } }
            OpeningChoice("ReadAlong text", "ReadAlong text", R.drawable.ic_highlighted_book, preferences.openingView in setOf("PREVIEW", "FULLSCREEN"), Modifier.weight(1f)) { save { it.copy(openingView = "PREVIEW") } }
        }
        OpeningChoice("Read only (no audio)", "Read only (no audio)", R.drawable.ic_book, preferences.openingView == "READ_ONLY", Modifier.fillMaxWidth()) { save { it.copy(openingView = "READ_ONLY") } }
        if (preferences.openingView in setOf("PREVIEW", "FULLSCREEN")) SettingsGroup {
            PreferenceSwitch("Open the text in full screen", preferences.openingView == "FULLSCREEN") { value -> save { it.copy(openingView = if (value) "FULLSCREEN" else "PREVIEW") } }
        }
        SectionLabel("Playback")
        SettingsGroup { SettingsRow("Rewind and forward intervals", onClick = { skipDialog = true }) }
        SectionLabel("ReadAlong")
        SettingsGroup {
            PreferenceSwitch("Only make ReadAlongs while charging", preferences.chargingOnly) { value -> scope.launch { app.appPreferences.update { it.copy(chargingOnly = value) }; app.processing.chargingPreferenceChanged() } }
            Text("Progress is saved. A ReadAlong pauses when you unplug and continues when you charge.", Modifier.padding(horizontal = 18.dp).padding(bottom = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsRow("Background work and battery", onClick = { battery = true })
        }
        SectionLabel("Other")
        SettingsGroup {
                SettingsRow("Offline speech models", onClick = onModel)
                SettingsRow("About ReadAlong", onClick = onAbout)
        }
        SectionLabel("Advanced")
        SettingsGroup {
            PreferenceSwitch("Animate the queue logo while working", preferences.animateTranscriptionLogo) { value -> save { it.copy(animateTranscriptionLogo = value) } }
            SettingsRow(if (copied) "Crash details copied" else "Copy crash details for support", onClick = { scope.launch {
                val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.readalong.data.diagnostics.CrashDiagnostics.read(app) }
                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("ReadAlong crash details", report))); copied = true
            } })
        }
        if (preferences.tipJarInOther) TipJarSection(true) { move -> save { it.copy(tipJarInOther = move) } }
        Spacer(Modifier.height(12.dp))
    } }
    if (appFont) com.example.readalong.ui.reading.AppFontDialog(preferences.appFont, { appFont = false }) { font -> save { it.copy(appFont = font) }; appFont = false }
    if (appearance) com.example.readalong.ui.reading.ReaderAppearance(reader, { appearance = false }) { value -> scope.launch { app.readerPreferences.save(value) }; appearance = false }
    if (colorPicker) BasicColorPicker(preferences.basicColor, { colorPicker = false }) { color -> pendingColor = color; colorPicker = false }
    if (pendingTheme != null || pendingColor != null) AlertDialog(
        onDismissRequest = { pendingTheme = null; pendingColor = null },
        title = { Text("Restart ReadAlong?") },
        text = { Text("ReadAlong restarts to update its icon and colors. Nothing is lost. If it closes, just open it again.") },
        confirmButton = { TextButton(onClick = {
            val light = pendingTheme; val color = pendingColor
            pendingTheme = null; pendingColor = null
            save { it.copy(libraryLight = light ?: it.libraryLight, basicColor = color ?: it.basicColor) }
        }) { Text("Restart") } },
        dismissButton = { TextButton(onClick = { pendingTheme = null; pendingColor = null }) { Text("Cancel") } })
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

@Composable private fun SectionLabel(value: String) { Text(value.uppercase(), Modifier.padding(top = 12.dp, start = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
@Composable private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))) { Column(Modifier.fillMaxWidth(), content = content) }
}
@Composable private fun SettingsRow(label: String, onClick: () -> Unit, trailing: @Composable () -> Unit = { Text("›", style = MaterialTheme.typography.titleLarge) }) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(label, Modifier.weight(1f)); trailing() }
}
@Composable private fun PreferenceSwitch(label: String, checked: Boolean, description: String = label, valueLabel: String? = null, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); valueLabel?.let { Text(it, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium) }
        Switch(checked, onCheckedChange = change, modifier = Modifier.semantics { contentDescription = description })
    }
}
@Composable private fun OpeningChoice(label: String, description: String, icon: Int, selected: Boolean, modifier: Modifier, choose: () -> Unit) {
    Surface(onClick = choose, modifier = modifier.semantics { contentDescription = description }, shape = RoundedCornerShape(20.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f), border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(painterResource(icon), null, Modifier.size(32.dp)); Text(label, style = MaterialTheme.typography.bodyMedium) }
    }
}
@Composable private fun SecondsField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, { change(it.filter(Char::isDigit).take(3)) }, label = { Text(label) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
}

@Composable private fun PreferenceCheckbox(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = change).padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Checkbox(checked, onCheckedChange = null)
    }
}