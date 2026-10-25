package com.example.readalong.ui.importing

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.readalong.R

/**
 * The one "Make a ReadAlong" step: from the audio, or matched to the person's EPUB, with a time
 * estimate. The first time, it also says what a ReadAlong is.
 */
@Composable
fun ReadAlongSourceDialog(busy: Boolean, message: String?, hasEpub: Boolean = false,
    onDismiss: () -> Unit, onStart: (Boolean, Uri?) -> Unit, bookTitle: String? = null, durationMs: Long? = null,
    book: com.example.readalong.domain.model.Book? = null) {
    MaterialTheme(colorScheme = com.example.readalong.ui.components.rememberBookColorScheme(book)) {
    var align by rememberSaveable { mutableStateOf(hasEpub) }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.example.readalong.ReadAlongApplication
    val settings = com.example.readalong.ui.theme.LocalAppSettings.current
    // Captured once, so the explanation stays while this dialog is open even after it's marked as seen.
    val explain = rememberSaveable { !settings.readAlongExplained }
    LaunchedEffect(Unit) { if (explain) app?.appPreferences?.update { it.copy(readAlongExplained = true) } }
    val estimate by produceState<String?>(null, durationMs) {
        value = app?.let { com.example.readalong.data.processing.TranscriptionEstimate.beforeStart(it.database, durationMs) }
    }
    var started by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { started = true; onStart(true, uri) }
    }
    com.example.readalong.ui.components.IllustratedDialog("Make a ReadAlong", { if (!busy) onDismiss() }, actions = {
        TextButton(enabled = !busy, onClick = onDismiss) { Text("Later") }
        Button(enabled = !busy, modifier = Modifier.testTag("make_readalong"), onClick = {
            if (align && !hasEpub) picker.launch(arrayOf("application/epub+zip", "application/octet-stream"))
            else { started = true; onStart(align, null) }
        }) { Text(if (align && !hasEpub) "Choose EPUB" else "Make ReadAlong", maxLines = 1, softWrap = false) }
    }) {
        bookTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        if (explain) Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)) {
            Text("A ReadAlong is your audiobook's text on screen, highlighted sentence by sentence as you listen. It's made on your phone, and nothing is uploaded.",
                Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val optionWidth = with(androidx.compose.ui.platform.LocalDensity.current) {
                ((maxWidth.toPx() - 8.dp.toPx()) / 2).toInt().toDp()
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingChoice("From the audio", "Works for any audiobook. A few words may be misheard.", R.drawable.art_book, !align,
                    Modifier.width(optionWidth).fillMaxHeight().testTag("reading_source_transcript")) { align = false }
                ReadingChoice("Match my ebook (EPUB)", if (hasEpub) "Uses the EPUB you added for the book's exact text." else "Uses the exact text of an ebook you own. You'll pick the .epub file next.",
                    R.drawable.art_epub, align, Modifier.width(optionWidth).fillMaxHeight().testTag("reading_source_epub")) { align = true }
            }
        }
        estimate?.let { Text("$it ReadAlong can run overnight while charging.", style = MaterialTheme.typography.bodyMedium) }
        // Only this sheet's own progress: folder scanning ("2 audiobooks scanned") stays out of it.
        if (started && busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (started) message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
}
