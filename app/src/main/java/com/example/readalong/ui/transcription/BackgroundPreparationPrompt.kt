package com.example.readalong.ui.transcription

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.ui.theme.LocalAppSettings
import kotlinx.coroutines.launch

/**
 * Shown once, when the first ReadAlong starts (not at first launch): battery use, "only while
 * charging", and Android's background permission, in one dialog. From Settings ([force]) it only
 * asks for background work.
 */
@Composable
@android.annotation.SuppressLint("BatteryLife") // Offline whole-book transcription is a core function impaired by idle restrictions.
fun BackgroundPreparationPrompt(app: ReadAlongApplication, force: Boolean = false, onClose: () -> Unit = {}, showNow: Boolean = false) {
    val context = LocalContext.current
    val settings = LocalAppSettings.current
    val queue by app.processing.queue.collectAsStateWithLifecycle(emptyList())
    var dismissed by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { dismissed = app.database.bookDao().metadata("battery_prompt_v1") != null }
    val scope = rememberCoroutineScope()
    val notify = rememberSpeechNotificationAction()
    val power = context.getSystemService(PowerManager::class.java)
    val exempt = power.isIgnoringBatteryOptimizations(context.packageName)
    var charging by remember { mutableStateOf(settings.chargingOnly) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onClose()
    }
    val working = queue.any { it.state in listOf("PROCESSING", "PARTIAL_READY", "GENERATING_EPUB") }
    val firstTime = !force && !settings.batteryWarningSeen
    fun finish() {
        dismissed = true
        scope.launch {
            app.database.bookDao().putMetadata(LibraryMetadataEntity("battery_prompt_v1", "shown"))
            if (!force) {
                val changed = charging != settings.chargingOnly
                app.appPreferences.update { it.copy(batteryWarningSeen = true, chargingOnly = charging) }
                if (changed) app.processing.chargingPreferenceChanged()
            }
        }
    }
    val askBackground = force || (dismissed == false && !exempt)
    val wanted = dismissed != null && (working || showNow) && (firstTime || askBackground)
    val myTurn = com.example.readalong.ui.components.OnboardingSheets.turn("background", !force && wanted)
    if (myTurn) SideEffect { com.example.readalong.ui.components.OnboardingSheets.pauseGuides() }
    if (force || myTurn) {
        com.example.readalong.ui.components.IllustratedDialog(if (firstTime) "Before your first ReadAlong" else "Keep working in the background", { finish(); onClose() },
            artwork = { if (firstTime) com.example.readalong.ui.components.ReadAlongArtwork(com.example.readalong.R.drawable.art_battery) else com.example.readalong.ui.components.BackgroundWaveform() },
            centered = !firstTime, actions = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (askBackground) Button(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = {
                        finish()
                        notify {
                            val intent = if (!exempt) Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            runCatching { launcher.launch(intent) }.onFailure {
                                launcher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            }
                        }
                    }) { Text("Allow background work", textAlign = TextAlign.Center) }
                    if (askBackground) TextButton(modifier = Modifier.fillMaxWidth(), onClick = { finish(); onClose() }) { Text("Not now") }
                    else Button(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), onClick = { finish(); onClose() }) { Text("Continue") }
                }
            }) {
            if (firstTime) Text("Making a ReadAlong for a whole book takes a while and uses battery. Progress is saved as it goes, and you can keep listening.")
            if (askBackground) Text("Allow ReadAlong to keep working while you use other apps or your screen is off.", modifier = Modifier.fillMaxWidth(),
                textAlign = if (firstTime) TextAlign.Start else TextAlign.Center)
            if (firstTime) Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().toggleable(charging, role = Role.Checkbox, onValueChange = { charging = it }).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(charging, onCheckedChange = null); Text("Only while charging")
                }
            }
        }
    }
}
