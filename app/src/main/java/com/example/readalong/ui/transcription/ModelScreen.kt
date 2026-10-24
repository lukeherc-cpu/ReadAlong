package com.example.readalong.ui.transcription

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
    var details by remember { mutableStateOf(false) }
    Scaffold { insets -> Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") }; Text("Offline speech model", style = MaterialTheme.typography.headlineSmall) }
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = if (status.installed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (status.installed) "Installed and ready" else "One-time download · 17 MB", style = MaterialTheme.typography.titleLarge)
                Text(if (status.installed) "ReadAlong can turn your audiobooks into text on this phone. Nothing is uploaded."
                    else "ReadAlong needs this download to turn speech into text on your phone. Nothing is uploaded.", style = MaterialTheme.typography.bodyLarge)
                Text("Works with English, German, French, Spanish, Italian, Dutch and Polish.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        status.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }; error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) {
            LinearProgressIndicator(progress = { (status.bytes.toFloat()/spec.size).coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth())
            Text("${status.bytes/1_000_000} of 17 MB")
            TextButton(onClick = model::cancel) { Text("Cancel download") }
        } else if (!status.installed) {
            Button(onClick = { notifyAction(model::download) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Download · 17 MB") }
        } else TextButton(onClick = { confirmRemove = true }) { Text("Remove speech model") }
        HorizontalDivider()
        TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) {
            Text("Technical details", style = MaterialTheme.typography.titleSmall)
            Icon(painterResource(R.drawable.ic_down), if (details) "Hide" else "Show", Modifier.size(18.dp).graphicsLayer { rotationZ = if (details) 180f else 0f })
        }
        if (details) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Model: Cactus Whistle, running on the Needle runtime (Apache-2.0). It's downloaded from Hugging Face, then checked (SHA-256) and tested on this phone before use.", style = MaterialTheme.typography.bodyMedium)
            Text("Speech recognition can mishear words. If you add an EPUB, its original text is always kept; audio is linked only where the match is reliable.", style = MaterialTheme.typography.bodyMedium)
            if (!busy && !status.installed) OutlinedButton(onClick = { notifyAction { picker.launch(arrayOf("*/*")) } }, modifier = Modifier.fillMaxWidth()) { Text("Install from a file (whistle.cact)") }
        }
    } }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Remove the speech model?") },
        text = { Text("You won't be able to make new ReadAlongs until you download it again. Your audiobooks, ReadAlongs, EPUBs and listening positions are kept.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; model.remove() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } })
}
