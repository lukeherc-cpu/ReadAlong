package com.example.readalong.ui.reading

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.readalong.data.preferences.ReaderFonts
import kotlinx.coroutines.launch

@Composable
internal fun AppFontDialog(value: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(value) }
    var imports by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { imports = ReaderFonts.imported(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null
            try {
                draft = ReaderFonts.import(context, uri).first
                imports = ReaderFonts.imported(context)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Couldn't import this font." }
            finally { busy = false }
        }
    }
    val choices = listOf("SANS" to "Sans serif", "SERIF" to "Serif", "MONO" to "Monospace") + ReaderFonts.bundled.map { it.first to it.second } + imports
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("App font") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FontChoice("Font", draft, choices) { draft = it }
            Text("Library, player and settings preview", fontFamily = readingFont(draft), modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text(if (busy) "Importing font…" else "Import font…") }
            Text("Accepted file types: TTF, OTF, TTC.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { onSave(draft) }) { Text("Apply") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}
