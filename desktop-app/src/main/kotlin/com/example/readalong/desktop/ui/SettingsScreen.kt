package com.example.readalong.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.example.readalong.desktop.core.AppPaths
import com.example.readalong.desktop.transcription.SpeechModels
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(app: AppState) {
    val settings by app.settingsStore.settings.collectAsState()
    val devices by app.transcription.devices.collectAsState()
    val installed by app.models.installed.collectAsState()
    val downloads by app.models.downloads.collectAsState()
    val folders by app.library.folders.collectAsState()
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 28.dp)) {
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIconButton("ic_back", "Back (Esc)", { app.back() })
                Spacer(Modifier.width(10.dp))
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
            }
            Column(Modifier.widthIn(max = 820.dp)) {
                SectionTitle("Transcription")
                val gpus = devices.orEmpty().filter { it.isGpu }
                Card {
                    Text(when {
                        devices == null -> "Checking this computer's graphics card…"
                        gpus.isEmpty() -> "No graphics card that ReadAlong can use was found, so it transcribes on the processor. Updating the graphics driver can help."
                        else -> "Graphics card: " + gpus.joinToString { "${it.description.ifBlank { it.name }} (${it.backend}${if (it.memoryTotal > 0) ", ${it.memoryTotal / (1L shl 30)} GB" else ""})" }
                    }, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    Text("Run on", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("auto" to "Graphics card if available", "cpu" to "Processor only").forEach { (key, label) ->
                            FilterChip(settings.device == key || (key == "auto" && settings.device == "gpu"), { app.settingsStore.update { it.copy(device = key) } }, { Text(label) })
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Language of your audiobooks", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("en" to "English", "auto" to "Detect", "de" to "German", "fr" to "French", "es" to "Spanish", "it" to "Italian", "nl" to "Dutch", "pl" to "Polish").forEach { (code, label) ->
                            FilterChip(settings.language == code, { app.settingsStore.update { it.copy(language = code) } }, { Text(label) })
                        }
                    }
                }
                SectionTitle("Speech model")
                val recommended = SpeechModels.recommended(devices.orEmpty(), settings.language)
                val chosen = app.transcription.model()
                SpeechModels.all.filter { settings.language == "en" || !it.englishOnly }.forEach { model ->
                    val progress = downloads[model.id]
                    Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(Corners.medium))
                        .clickable { app.settingsStore.update { it.copy(modelId = model.id) } }.pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(Corners.medium),
                        color = if (chosen.id == model.id) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = if (chosen.id == model.id) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .6f)) else null) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(chosen.id == model.id, { app.settingsStore.update { it.copy(modelId = model.id) } })
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(model.name, style = MaterialTheme.typography.titleSmall)
                                    if (model.id == recommended.id) {
                                        Spacer(Modifier.width(8.dp))
                                        Text("Recommended", Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                                Text(model.summary + " ${model.sizeBytes / 1_000_000} MB.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (progress != null) { Spacer(Modifier.height(8.dp)); ProgressLine(progress) }
                            }
                            when {
                                progress != null -> Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                                model.id in installed -> TextButton({ app.models.delete(model) }) { Text("Delete") }
                                else -> TextButton({ scope.launch { runCatching { app.models.download(model) }.onFailure { app.messages.emit(it.message ?: "Download failed.") } } }) { Text("Download") }
                            }
                        }
                    }
                }
                Text("Models download from Hugging Face the first time they're used, then work offline.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                SectionTitle("Phone")
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Save each ReadAlong beside its audiobook", style = MaterialTheme.typography.titleSmall)
                            Text("Writes <book>.readalong next to the audio files, in the format the phone app imports. When the same folder is on your phone, " +
                                "its library scan picks the transcript up, so the phone doesn't need to transcribe the book again. Transcripts made on the phone " +
                                "or with ReadAlong Desktop's transcriber are read the same way here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(settings.savePackages, { v -> app.settingsStore.update { it.copy(savePackages = v) } })
                    }
                }

                SectionTitle("Library folders")
                Card {
                    if (folders.isEmpty()) Text("No folders yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    folders.forEach { folder ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.get("ic_folder"), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(folder, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton({ app.library.setFolders(folders - folder) }) { Text("Remove") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Add folder", { chooseFolder()?.let { app.addPaths(listOf(it)) } }, filled = false, icon = "ic_add")
                        PillButton("Rescan", { scope.launch { app.importer.rescan() } }, filled = false)
                    }
                }

                SectionTitle("Playback")
                Card {
                    Stepper("Skip back", settings.skipBackSeconds, { v -> app.settingsStore.update { it.copy(skipBackSeconds = v) } })
                    Stepper("Skip forward", settings.skipForwardSeconds, { v -> app.settingsStore.update { it.copy(skipForwardSeconds = v) } })
                    Text("Keyboard: Space plays and pauses, ← and → skip, Esc goes back, Ctrl+F searches the text.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                SectionTitle("Colour")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccentPalettes.forEach { p ->
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(p.color)).clickable { app.settingsStore.update { it.copy(accent = p.color) } }
                            .pointerHoverIcon(PointerIcon.Hand).then(if (settings.accent == p.color) Modifier.border(3.dp, Color.White, CircleShape) else Modifier))
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("ReadAlong for Windows ${System.getProperty("readalong.version") ?: "dev"} · Data in ${AppPaths.data}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(40.dp))
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(Corners.large), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Text(label, Modifier.weight(1f))
        OutlinedButton({ onChange((value - 5).coerceAtLeast(5)) }) { Text("−") }
        Text("$value s", Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton({ onChange((value + 5).coerceAtMost(120)) }) { Text("+") }
    }
}
