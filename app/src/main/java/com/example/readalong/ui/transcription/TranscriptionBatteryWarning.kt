package com.example.readalong.ui.transcription

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.readalong.ReadAlongApplication
import com.example.readalong.ui.theme.LocalAppSettings
import kotlinx.coroutines.launch

@Composable
fun TranscriptionBatteryWarning(app: ReadAlongApplication) {
    val settings = LocalAppSettings.current
    val scope = rememberCoroutineScope()
    var charging by remember { mutableStateOf(settings.chargingOnly) }
    com.example.readalong.ui.components.IllustratedDialog("Offline transcription uses battery", {},
        artwork = { com.example.readalong.ui.components.ReadAlongArtwork(com.example.readalong.R.drawable.art_battery) },
        actions = { Button(onClick = { scope.launch { app.appPreferences.update { it.copy(chargingOnly = charging, batteryWarningSeen = true, setupStage = 3) } } }, modifier = Modifier.fillMaxWidth()) { Text("Continue →") } }) {
        Text("Turning a whole audiobook into text can use significant battery and warm your device. Your progress is saved automatically, and playback remains available.")
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().toggleable(charging, role = Role.Checkbox, onValueChange = { charging = it }).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(charging, onCheckedChange = null); Text("Only transcribe while charging")
            }
        }
    }
}
