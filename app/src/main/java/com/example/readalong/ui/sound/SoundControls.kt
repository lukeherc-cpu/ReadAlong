package com.example.readalong.ui.sound

import com.example.readalong.domain.i18n.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.res.painterResource
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
        Text(tr(label), Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(value.toFloat(), { change(it.roundToInt()) }, Modifier.weight(1f).semantics { contentDescription = tr(label); stateDescription = text },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1)
        Text(tr(text), Modifier.widthIn(min = 56.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SoundSwitch(label: String, summary: String?, checked: Boolean, change: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = change).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr(label), style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(tr(summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                FilterChip(value.equalizer && value.preset == preset, { change(value.copy(equalizer = true, preset = preset)) }, { Text(tr(preset.label)) },
                    Modifier.testTag("eq_preset_${preset.name}"))
            }
        }
        EqBands.forEachIndexed { index, hz ->
            val db = value.gains[index]
            StepSlider(bandText(hz), db, -EQ_MAX_DB..EQ_MAX_DB, signed(db, " dB"), { change(value.withBand(index, it)) }, Modifier.testTag("eq_band_$index"))
        }
        TextButton(onClick = { change(value.copy(preset = EqPreset.FLAT, customGains = EqPreset.FLAT.gains)) },
            Modifier.padding(start = 8.dp).testTag("eq_reset")) { Text(tr("Reset to default")) }
    }
}

/**
 * Volume, pitch, noise reduction and skip silence, then one Equalizer row showing the preset, shared by
 * Settings and a book's Sound sheet. The equalizer itself opens on its own page ([onEqualizer]).
 */
@Composable
fun SoundBasics(value: SoundSettings, change: (SoundSettings) -> Unit, onEqualizer: () -> Unit) {
    StepSlider("Volume", value.volumeDb, -VOLUME_MAX_DB..VOLUME_MAX_DB, volumeText(value.volumeDb), { change(value.copy(volumeDb = it)) }, Modifier.testTag("sound_volume"))
    StepSlider("Pitch", value.pitch, -PITCH_MAX..PITCH_MAX, pitchText(value.pitch), { change(value.copy(pitch = it)) }, Modifier.testTag("sound_pitch"))
    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
    SoundSwitch("Noise reduction", null, value.noiseReduction, { change(value.copy(noiseReduction = it)) })
    SoundSwitch("Skip silence", null, value.skipSilence, { change(value.copy(skipSilence = it)) })
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = "Open equalizer", onClick = onEqualizer)
        .padding(horizontal = 16.dp, vertical = 8.dp).testTag("sound_equalizer"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("Equalizer"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(tr(equalizerText(value)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(painterResource(com.example.readalong.R.drawable.ic_chevron_right), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A short summary of non-default sound choices for a Settings row, like "Voice clarity · Skip silence". */
fun soundSummary(value: SoundSettings): String = listOfNotNull(
    value.preset.label.takeIf { value.equalizer },
    volumeText(value.volumeDb).takeIf { value.volumeDb != 0 }?.let { "Volume $it" },
    pitchText(value.pitch).takeIf { value.pitch != 0 }?.let { "Pitch $it" },
    "Noise reduction".takeIf { value.noiseReduction },
    "Skip silence".takeIf { value.skipSilence },
).joinToString(" · ").ifEmpty { "Normal" }

/**
 * One book's sound, from the player's ⋯ menu or a long press on its cover. The first change gives the
 * book its own copy of the Settings defaults; "Use my defaults" removes it again. The equalizer opens
 * as its own sheet, and closing it comes back here.
 */
@Composable
fun BookSoundSheet(app: ReadAlongApplication, bookId: String, title: String, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val defaults by app.sound.defaults.collectAsStateWithLifecycle(SoundSettings.DEFAULT)
    val own by app.sound.own(bookId).collectAsStateWithLifecycle(null)
    val value = own ?: defaults
    var equalizer by androidx.compose.runtime.saveable.rememberSaveable(bookId) { mutableStateOf(false) }
    fun change(next: SoundSettings) { scope.launch { app.sound.saveForBook(bookId, next) } }
    if (equalizer) {
        com.example.readalong.ui.components.ActionSheet({ equalizer = false }, "Equalizer", title) {
            item { EqualizerEditor(value, ::change) }
        }
        return
    }
    com.example.readalong.ui.components.ActionSheet(close, "Sound", if (own != null) "Just for this book" else "Your defaults") {
        item { Column { SoundBasics(value, ::change) { equalizer = true } } }
        if (own != null) item {
            TextButton(onClick = { scope.launch { app.sound.resetBook(bookId) } }, Modifier.padding(start = 8.dp).testTag("sound_use_defaults")) { Text(tr("Use my defaults")) }
        }
    }
}
