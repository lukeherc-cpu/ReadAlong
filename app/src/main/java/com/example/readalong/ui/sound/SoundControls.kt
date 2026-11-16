package com.example.readalong.ui.sound

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.ReadAlongApplication
import com.example.readalong.domain.model.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun signed(value: Int, unit: String = "") = when {
    value > 0 -> "+$value$unit"
    value < 0 -> "−${-value}$unit"
    else -> if (unit.isEmpty()) "Normal" else "0$unit"
}
fun volumeText(db: Int) = if (db == 0) "Normal" else signed(db, " dB")
fun pitchText(steps: Int) = if (steps == 0) "Normal" else signed(steps)
fun equalizerText(value: SoundSettings) = if (value.equalizer) value.preset.label else "Off"
private fun bandText(hz: Int) = if (hz >= 1000) "${if (hz % 1000 == 0) hz / 1000 else hz / 1000.0} kHz" else "$hz Hz"

/** A labelled whole-number slider with Normal in the middle, for volume, pitch and the equalizer bands. */
@Composable
fun StepSlider(label: String, value: Int, range: IntRange, text: String, change: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(value.toFloat(), { change(it.roundToInt()) }, Modifier.weight(1f).semantics { contentDescription = label; stateDescription = text },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1)
        Text(text, Modifier.widthIn(min = 56.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SoundSwitch(label: String, summary: String?, checked: Boolean, change: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = change).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}

/** Equalizer: on/off, speech presets, five bands and Reset to default (a flat curve). */
@Composable
fun EqualizerEditor(value: SoundSettings, change: (SoundSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SoundSwitch("Equalizer", null, value.equalizer, { change(value.copy(equalizer = it)) }, Modifier.testTag("equalizer_switch"))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EqPreset.entries.forEach { preset ->
                FilterChip(value.equalizer && value.preset == preset, { change(value.copy(equalizer = true, preset = preset)) }, { Text(preset.label) },
                    Modifier.testTag("eq_preset_${preset.name}"))
            }
        }
        EqBands.forEachIndexed { index, hz ->
            val db = value.gains[index]
            StepSlider(bandText(hz), db, -EQ_MAX_DB..EQ_MAX_DB, signed(db, " dB"), { change(value.withBand(index, it)) }, Modifier.testTag("eq_band_$index"))
        }
        TextButton(onClick = { change(value.copy(preset = EqPreset.FLAT, customGains = EqPreset.FLAT.gains)) },
            Modifier.padding(start = 8.dp).testTag("eq_reset")) { Text("Reset to default") }
    }
}

/** Volume, pitch, noise reduction and skip silence, shared by Settings and a book's Sound sheet. */
@Composable
fun SoundBasics(value: SoundSettings, change: (SoundSettings) -> Unit) {
    StepSlider("Volume", value.volumeDb, -VOLUME_MAX_DB..VOLUME_MAX_DB, volumeText(value.volumeDb), { change(value.copy(volumeDb = it)) }, Modifier.testTag("sound_volume"))
    StepSlider("Pitch", value.pitch, -PITCH_MAX..PITCH_MAX, pitchText(value.pitch), { change(value.copy(pitch = it)) }, Modifier.testTag("sound_pitch"))
    SoundSwitch("Noise reduction", "Softens hiss and rumble on older recordings", value.noiseReduction, { change(value.copy(noiseReduction = it)) })
    SoundSwitch("Skip silence", "Shortens long pauses between sentences", value.skipSilence, { change(value.copy(skipSilence = it)) })
}

/**
 * One book's sound, from the player's ⋯ menu or a long press on its cover. The first change gives the
 * book its own copy of the Settings defaults; "Use my defaults" removes it again.
 */
@Composable
fun BookSoundSheet(app: ReadAlongApplication, bookId: String, title: String, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val defaults by app.sound.defaults.collectAsStateWithLifecycle(SoundSettings.DEFAULT)
    val own by app.sound.own(bookId).collectAsStateWithLifecycle(null)
    val value = own ?: defaults
    fun change(next: SoundSettings) { scope.launch { app.sound.saveForBook(bookId, next) } }
    com.example.readalong.ui.components.ActionSheet(close, "Sound", if (own != null) "Just for this book" else "Your defaults") {
        item {
            Text(if (own != null) "Changes here apply only to $title." else "$title uses the defaults from Settings. Changing anything here keeps it for this book only.",
                Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Column { SoundBasics(value, ::change) } }
        item { HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item { EqualizerEditor(value, ::change) }
        if (own != null) item {
            TextButton(onClick = { scope.launch { app.sound.resetBook(bookId) } }, Modifier.padding(start = 8.dp).testTag("sound_use_defaults")) { Text("Use my defaults from Settings") }
        }
    }
}
