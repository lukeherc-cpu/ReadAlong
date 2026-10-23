package com.example.readalong.ui.transcription

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.local.LibraryMetadataEntity
import kotlinx.coroutines.launch

/** Explain the request at the first transcription, and let the user keep normal battery limits. */
@Composable
@android.annotation.SuppressLint("BatteryLife") // Offline whole-book transcription is a core function impaired by idle restrictions.
fun BackgroundPreparationPrompt(app: ReadAlongApplication, force: Boolean = false, onClose: () -> Unit = {}) {
    val context = LocalContext.current
    val queue by app.processing.queue.collectAsStateWithLifecycle(emptyList())
    var dismissed by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { dismissed = app.database.bookDao().metadata("battery_prompt_v1") != null }
    val scope = rememberCoroutineScope()
    val notify = rememberSpeechNotificationAction()
    val power = context.getSystemService(PowerManager::class.java)
    val exempt = power.isIgnoringBatteryOptimizations(context.packageName)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onClose()
    }
    fun finish() { dismissed = true; scope.launch { app.database.bookDao().putMetadata(LibraryMetadataEntity("battery_prompt_v1", "shown")) } }
    if (force || (dismissed == false && !exempt && queue.any { it.state in listOf("PROCESSING", "PARTIAL_READY", "GENERATING_EPUB") })) {
        com.example.readalong.ui.components.IllustratedDialog("Keep transcribing in the background", { finish(); onClose() },
            artwork = { com.example.readalong.ui.components.BackgroundWaveform() }, centered = true, actions = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = {
                        finish()
                        notify {
                            val intent = if (!exempt) Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            runCatching { launcher.launch(intent) }.onFailure {
                                launcher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            }
                        }
                    }) { Text("Allow background processing", textAlign = TextAlign.Center) }
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { finish(); onClose() }) { Text("Not now") }
                }
            }) {
            Text("Allow ReadAlong to continue transcribing while you use other apps or your screen is off.", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}
