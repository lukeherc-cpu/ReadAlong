package com.example.readalong.ui.reading

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.example.readalong.data.preferences.ReaderPreferences
import com.example.readalong.domain.model.Book
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.example.readalong.data.preferences.ReaderFonts

@Composable
internal fun ReaderAppearance(value: ReaderPreferences, onDismiss: () -> Unit, bookMode: Boolean = false, onSave: (ReaderPreferences) -> Unit) {
    var draft by remember { mutableStateOf(value) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var fontImports by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { fontImports = ReaderFonts.imported(context) }
    val importFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true; importError = null
            try {
                val imported = ReaderFonts.import(context, uri)
                fontImports = ReaderFonts.imported(context)
                draft = draft.copy(fontFamily = imported.first, useEpubFonts = false)
            } catch (failure: Exception) { importError = failure.message ?: "Couldn't import this font." }
            finally { importing = false }
        }
    }
    val choices = listOf("SERIF" to "Serif", "SANS" to "Sans serif", "MONO" to "Monospace") + ReaderFonts.bundled.map { it.first to it.second } + fontImports
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Reading appearance") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            FontChoice("Reader font", draft.fontFamily, listOf("EPUB" to "EPUB / serif") + choices) { draft = draft.copy(fontFamily = it) }
            FontChoice("Preview font", draft.previewFont, listOf("MATCH" to "Match reader") + choices) { draft = draft.copy(previewFont = it) }
            TextButton(enabled = !importing, onClick = { importFont.launch(arrayOf("*/*")) }) { Text(if (importing) "Importing font…" else "Import font…") }
            Text("Accepted file types: TTF, OTF, TTC.", style = MaterialTheme.typography.bodySmall)
            importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Use EPUB fonts", Modifier.weight(1f))
                Switch(draft.useEpubFonts, { draft = draft.copy(useEpubFonts = it) })
            }
            Text("Font size · ${draft.fontSize.toInt()}")
            Slider(draft.fontSize, { draft = draft.copy(fontSize = it) }, valueRange = 16f..32f, modifier = Modifier.testTag("reader_font_size"))
            Text("Line spacing · ${"%.1f".format(draft.lineSpacing)}")
            Slider(draft.lineSpacing, { draft = draft.copy(lineSpacing = it) }, valueRange = 1.2f..2.2f)
            Text("Margins · ${draft.margin.toInt()}")
            Slider(draft.margin, { draft = draft.copy(margin = it) }, valueRange = 12f..40f)
            Row { (if (bookMode) listOf("LIGHT", "DARK") else listOf("DARK", "LIGHT", "SEPIA")).forEach { theme ->
                FilterChip(selected = draft.theme == theme, onClick = { draft = draft.copy(theme = theme) }, label = { Text(theme.lowercase().replaceFirstChar { it.uppercase() }) })
            } }
        }
    }, confirmButton = { TextButton(enabled = !importing, onClick = { onSave(draft) }) { Text("Apply") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}


private val importedFontCache = java.util.concurrent.ConcurrentHashMap<String, FontFamily>()

@Composable
internal fun readingFont(name: String): FontFamily {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bundled = ReaderFonts.bundled.firstOrNull { it.first == name }
    if (bundled != null) return remember(name) { FontFamily(androidx.compose.ui.text.font.Font(bundled.third, weight = if (name == "JAKARTA") androidx.compose.ui.text.font.FontWeight.ExtraBold else androidx.compose.ui.text.font.FontWeight.Normal)) }
    val imported by produceState<FontFamily?>(importedFontCache[name], name) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ReaderFonts.file(context, name)?.takeIf { it.exists() }?.let { file ->
                importedFontCache[name] ?: runCatching { FontFamily(android.graphics.Typeface.createFromFile(file)) }.getOrNull()?.also { importedFontCache[name] = it }
            }
        }
    }
    return imported ?: when (name) {
        "SANS" -> FontFamily.SansSerif
        "MONO" -> FontFamily.Monospace
        else -> FontFamily.Serif
    }
}

@Composable
internal fun FontChoice(label: String, value: String, choices: List<Pair<String, String>>, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("$label · ${choices.firstOrNull { it.first == value }?.second.orEmpty()}") }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { choose(key); expanded = false }) }
        }
    }
}
