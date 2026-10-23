package com.example.readalong.ui.importing

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.readalong.R

/** Displayed only after the user explicitly chooses Create ReadAlong. */
@Composable
fun ReadAlongSourceDialog(busy: Boolean, message: String?, hasEpub: Boolean = false,
    onDismiss: () -> Unit, onStart: (Boolean, Uri?) -> Unit) {
    var align by rememberSaveable { mutableStateOf(true) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onStart(true, uri)
    }
    com.example.readalong.ui.components.IllustratedDialog("Create ReadAlong", { if (!busy) onDismiss() }, actions = {
        TextButton(enabled = !busy, onClick = onDismiss) { Text("Later") }
        Button(enabled = !busy, onClick = {
            if (align && !hasEpub) picker.launch(arrayOf("application/epub+zip", "application/octet-stream"))
            else onStart(align, null)
        }) { Text(if (align && !hasEpub) "Select EPUB" else "Create ReadAlong", maxLines = 1, softWrap = false) }
    }) {
        Text("Choose how you’d like to create the read-along text for this audiobook.")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val optionWidth = with(androidx.compose.ui.platform.LocalDensity.current) {
                ((maxWidth.toPx() - 8.dp.toPx()) / 2).toInt().toDp()
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingChoice("Use ReadAlong transcription only", "≈95% accurate", R.drawable.art_book, !align, Modifier.width(optionWidth).fillMaxHeight().testTag("reading_source_transcript")) { align = false }
                ReadingChoice("Align read-along transcript with EPUB", "100% accurate", R.drawable.art_epub, align, Modifier.width(optionWidth).fillMaxHeight().testTag("reading_source_epub")) { align = true }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
