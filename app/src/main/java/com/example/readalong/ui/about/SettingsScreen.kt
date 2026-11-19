package com.example.readalong.ui.about

import com.example.readalong.domain.i18n.tr
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveable
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
    var equalizerPage by rememberSaveable { mutableStateOf(false) }
    /** The Languages page's open tab (0 books, 1 app), or -1 while it's closed. */
    var languagePage by rememberSaveable { mutableIntStateOf(-1) }
    var resumeDialog by remember { mutableStateOf(false) }
    var resetSoundSheet by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val soundFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri: android.net.Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            save { it.copy(resetSound = "FILE", resetSoundUri = uri.toString()) }
        }
    }
    val sound by app.sound.defaults.collectAsStateWithLifecycle(com.example.readalong.domain.model.SoundSettings.DEFAULT)
    fun saveSound(value: com.example.readalong.domain.model.SoundSettings) { scope.launch { app.sound.saveDefaults(value) } }
    if (equalizerPage) {
        androidx.activity.compose.BackHandler { equalizerPage = false }
        Scaffold { insets -> Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { equalizerPage = false }) { Icon(painterResource(R.drawable.ic_back), tr("Back to settings")) }
                Text(tr("Equalizer"), style = MaterialTheme.typography.headlineMedium)
            }
            Text(tr("The default for every book. A book changed from its own Sound sheet keeps its own equalizer."),
                Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            com.example.readalong.ui.sound.EqualizerEditor(sound, ::saveSound)
        } }
        return
    }
    if (languagePage >= 0) {
        LanguageSettingsPage(preferences.transcriptionLanguage, languagePage, onBack = { languagePage = -1 }) { code -> save { it.copy(transcriptionLanguage = code) } }
        return
    }
    Scaffold { insets -> Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), tr("Back to library")) }
            Text(tr("Settings"), style = MaterialTheme.typography.headlineMedium)
        }
        if (!preferences.tipJarInOther) TipJarSection(false) { move -> save { it.copy(tipJarInOther = move) } }
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
            Text(tr("Open books to"), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OpeningChoices(preferences.openingView) { view -> save { it.copy(openingView = view) } }
            SettingsGroup {
                if (preferences.openingView in setOf("PREVIEW", "FULLSCREEN"))
                    PreferenceSwitch("Open the text in full screen", preferences.openingView == "FULLSCREEN") { value -> save { it.copy(openingView = if (value) "FULLSCREEN" else "PREVIEW") } }
                SettingsRow("Player background", onClick = { backgroundPicker = true }) { ValueText(if (preferences.blurredPlayer) "Blurred cover" else "Solid color"); Chevron() }
                SettingsRow("Reading fonts and appearance", onClick = { appearance = true })
                SettingsRow("Rewind and forward times", onClick = { skipDialog = true }) { ValueText("${preferences.playerBack}s · ${preferences.playerForward}s"); Chevron() }
                SettingsRow("Rewind when resuming", Modifier.testTag("settings_resume"), summary = "Goes back a little after a pause", onClick = { resumeDialog = true }) { ValueText(resumeLabel(preferences.resumeRewind)); Chevron() }
                PreferenceSwitch("Remember speed for each book", preferences.speedPerBook) { value -> save { it.copy(speedPerBook = value) } }
            }
        }
        SettingsSection("Sound") {
            SettingsGroup {
                SettingsRow("Equalizer", Modifier.testTag("settings_equalizer"), onClick = { equalizerPage = true }) { ValueText(com.example.readalong.ui.sound.equalizerText(sound)); Chevron() }
                Column(Modifier.padding(vertical = 8.dp)) { com.example.readalong.ui.sound.SoundBasics(sound, ::saveSound) }
            }
            Text(tr("Defaults for every book. Change one book from the player's ⋯ menu or by holding its cover in the library."),
                Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsSection("Sleep timer") {
            SettingsGroup {
                PreferenceSwitch("Shake to reset", preferences.shakeToReset, summary = "Shake the phone to start the timer again, even while it fades") { value -> save { it.copy(shakeToReset = value) } }
                SettingsRow("Reset sound", Modifier.testTag("settings_reset_sound"), onClick = { resetSoundSheet = true }) { ValueText(resetSoundLabel(preferences.resetSound)); Chevron() }
            }
            Text(tr("Playback fades out over the last 20 seconds."), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsSection("Languages") {
            SettingsGroup {
                SettingsRow("Book transcription", Modifier.testTag("settings_language"), summary = "The language your audiobooks are read in", onClick = { languagePage = 0 }) {
                    ValueText(if (preferences.transcriptionLanguage == com.example.readalong.domain.transcription.TranscriptionLanguages.AUTO) "Detect"
                        else com.example.readalong.domain.i18n.Translations.languageName(preferences.transcriptionLanguage, com.example.readalong.domain.transcription.TranscriptionLanguages.name(preferences.transcriptionLanguage))); Chevron()
                }
                SettingsRow("App language", Modifier.testTag("settings_app_language"), summary = "Menus, buttons and messages", onClick = { languagePage = 1 }) {
                    ValueText(AppLanguage.name(AppLanguage.current(context))); Chevron()
                }
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
                SettingsRow(if (copied) "Crash details copied" else "Copy crash details for support", onClick = { scope.launch {
                    val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.example.readalong.data.diagnostics.CrashDiagnostics.read(app) + com.example.readalong.data.diagnostics.CrashDiagnostics.transcriptionFailures(app.database)
                    }
                    clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("ReadAlong crash details", report))); copied = true
                } })
            }
        }
        if (preferences.tipJarInOther) TipJarSection(true) { move -> save { it.copy(tipJarInOther = move) } }
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
    if (backgroundPicker) com.example.readalong.ui.components.ActionSheet({ backgroundPicker = false }, "Player background") {
        items(listOf(true to "Blurred cover art", false to "Solid color")) { (blur, label) ->
            com.example.readalong.ui.components.SheetRow(label, { save { it.copy(blurredPlayer = blur) }; backgroundPicker = false }, selected = preferences.blurredPlayer == blur)
        }
    }
    if (skipDialog) {
        var back by remember { mutableStateOf(preferences.playerBack.toString()) }; var forward by remember { mutableStateOf(preferences.playerForward.toString()) }
        var readerBack by remember { mutableStateOf(preferences.readerBack.toString()) }; var readerForward by remember { mutableStateOf(preferences.readerForward.toString()) }
        val valid = listOf(back, forward, readerBack, readerForward).all { (it.toIntOrNull() ?: 0) in 1..600 }
        // One back/forward pair; full-screen reading has its own (5 s by default) behind an expander.
        var readerTimes by remember { mutableStateOf(preferences.readerBack != 5 || preferences.readerForward != 5) }
        AlertDialog(onDismissRequest = { skipDialog = false }, title = { Text(tr("Rewind and forward")) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("How far the rewind and forward buttons jump, in seconds (1 to 600)."))
                SecondsField("Rewind", back) { back = it }; SecondsField("Forward", forward) { forward = it }
                TextButton(onClick = { readerTimes = !readerTimes }, contentPadding = PaddingValues(0.dp)) {
                    Text(tr(if (readerTimes) "Full-screen reading times" else "Change full-screen reading times"))
                }
                if (readerTimes) { SecondsField("Full-screen rewind", readerBack) { readerBack = it }; SecondsField("Full-screen forward", readerForward) { readerForward = it } }
            }
        }, confirmButton = { TextButton(enabled = valid, onClick = {
            save { it.copy(playerBack = back.toInt(), playerForward = forward.toInt(), readerBack = readerBack.toInt(), readerForward = readerForward.toInt()) }; skipDialog = false
        }) { Text(tr("Save")) } }, dismissButton = { TextButton(onClick = { skipDialog = false }) { Text(tr("Cancel")) } })
    }
    if (battery) com.example.readalong.ui.transcription.BackgroundPreparationPrompt(app, force = true, onClose = { battery = false })
    if (resumeDialog) {
        var choice by remember { mutableStateOf(preferences.resumeRewind) }
        AlertDialog(onDismissRequest = { resumeDialog = false }, title = { Text(tr("Rewind when resuming")) }, text = {
            Column(Modifier.selectableGroup()) {
                Text(tr("When you press play after a pause, start a little earlier so you don't lose the thread."), Modifier.padding(bottom = 8.dp))
                com.example.readalong.data.preferences.ResumeRewinds.forEach { mode ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(choice == mode, role = androidx.compose.ui.semantics.Role.RadioButton) { choice = mode }.testTag("resume_$mode"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(choice == mode, onClick = null)
                        Column {
                            Text(tr(resumeLabel(mode)))
                            resumeSummary(mode)?.let { Text(tr(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { save { it.copy(resumeRewind = choice) }; resumeDialog = false }) { Text(tr("OK")) } },
            dismissButton = { TextButton(onClick = { resumeDialog = false }) { Text(tr("Cancel")) } })
    }
    if (resetSoundSheet) com.example.readalong.ui.components.ActionSheet({ resetSoundSheet = false }, "Reset sound", "Plays when a shake resets the timer") {
        items(com.example.readalong.data.preferences.ResetSounds) { sound ->
            com.example.readalong.ui.components.SheetRow(if (sound == "FILE") "Choose a sound file…" else resetSoundLabel(sound), {
                if (sound == "FILE") soundFile.launch(arrayOf("audio/*"))
                else { save { it.copy(resetSound = sound) }; com.example.readalong.playback.SleepShake.playResetSound(context, preferences.copy(resetSound = sound)) }
            }, selected = preferences.resetSound == sound, icon = if (sound == "FILE") R.drawable.ic_folder else null)
        }
        item {
            var volume by remember { mutableFloatStateOf(preferences.resetVolume.toFloat()) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Volume"), style = MaterialTheme.typography.bodyMedium)
                Slider(volume, { volume = it }, Modifier.weight(1f).testTag("reset_volume"), valueRange = 0f..100f, onValueChangeFinished = {
                    save { it.copy(resetVolume = volume.toInt()) }
                    com.example.readalong.playback.SleepShake.playResetSound(context, preferences.copy(resetVolume = volume.toInt()))
                })
                Text(tr("${volume.toInt()}%"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Quiet, sentence-case headings so the rows, not the labels, carry the page.
@Composable private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(title), Modifier.padding(start = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
@Composable private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = MaterialTheme.colorScheme.surfaceContainer) { Column(Modifier.fillMaxWidth(), content = content) }
}
@Composable internal fun Chevron() = Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
/** A row's current value, shown before its chevron. */
@Composable internal fun ValueText(value: String) = Text(tr(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
@Composable private fun RowLabel(label: String, summary: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(tr(label), style = MaterialTheme.typography.bodyLarge)
        if (summary != null) Text(tr(summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        .semantics { contentDescription = tr(description) }.padding(horizontal = 16.dp, vertical = 8.dp),
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
                modifier = Modifier.semantics { contentDescription = tr(description) }) { Text(tr(label), maxLines = 1) }
        }
    }
}
@Composable private fun SecondsField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, { change(it.filter(Char::isDigit).take(3)) }, label = { Text(tr(label)) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
}

private fun resumeLabel(mode: String) = when (mode) {
    "OFF" -> "Off"; "SHORT" -> "Always 3 seconds"; "LONG" -> "Always 10 seconds"; "SENTENCE" -> "Start of the sentence"; else -> "Smart"
}
private fun resumeSummary(mode: String) = when (mode) {
    "SMART" -> "2 s after a short pause, 10 s after 5 min, 30 s after an hour"
    "SENTENCE" -> "Uses the transcript"
    else -> null
}
private fun resetSoundLabel(sound: String) = when (sound) {
    "BEEPS" -> "Two beeps"; "VIBRATE" -> "Vibrate only"; "NONE" -> "None"; "FILE" -> "Your sound file"; else -> "Soft chime"
}
