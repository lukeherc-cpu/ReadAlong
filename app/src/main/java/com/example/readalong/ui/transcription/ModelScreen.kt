package com.example.readalong.ui.transcription

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.R
import com.example.readalong.domain.transcription.SpeechModels

@Composable
fun ModelScreen(model: ModelViewModel, onBack: () -> Unit) {
    val status by model.status.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val spec = SpeechModels.whistle
    var confirmRemove by remember { mutableStateOf(false) }
    val notifyAction = rememberSpeechNotificationAction()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::import) }
    Scaffold { insets -> Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") }; Text("Offline speech model", style = MaterialTheme.typography.headlineSmall) }
        Text("Cactus Whistle · 17 MB", style = MaterialTheme.typography.titleLarge)
        Text("Free audio to text on your device. Supports English, German, French, Spanish, Italian, Dutch and Polish. Audiobook audio and recognized text stay on your device.")
        Text("Whistle prepares the whole book in the background, saving progress after short audio sections. Its word timestamps connect the original EPUB sentences to the audio.")
        status.message?.let { Text(it) }; error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) {
            LinearProgressIndicator(progress = { (status.bytes.toFloat()/spec.size).coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth())
            Text("${status.bytes/1_000_000} of 17 MB received")
            TextButton(onClick = model::cancel) { Text("Cancel installation") }
        } else if (!status.installed) {
            Button(onClick = { notifyAction(model::download) }, modifier = Modifier.fillMaxWidth()) { Text("Download Whistle · 17 MB") }
            OutlinedButton(onClick = { notifyAction { picker.launch(arrayOf("*/*")) } }, modifier = Modifier.fillMaxWidth()) { Text("Install downloaded whistle.cact") }
            Text("Download fetches the free model from Hugging Face. A SHA-256 check and a local test verify it before use. Whistle downloads automatically. You can cancel, retry or install a downloaded model here.", style = MaterialTheme.typography.bodySmall)
        } else TextButton(onClick = { confirmRemove = true }) { Text("Remove Whistle model") }
        Text("Saved transcripts and audio links remain readable after this update. Resume an unfinished book to prepare it with Whistle from the beginning; use Rebuild for a completed book. Your saved listening position and EPUB are kept.", style = MaterialTheme.typography.bodySmall)
        Text("Cactus Whistle / Needle runtime · Apache-2.0. Recognition can mishear speech. Original EPUB text stays intact; audio links require a reliable match.", style = MaterialTheme.typography.bodySmall)
    } }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Remove Whistle?") },
        text = { Text("Transcription will stop. Audiobooks, saved text, EPUBs and listening positions are kept.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; model.remove() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } })
}