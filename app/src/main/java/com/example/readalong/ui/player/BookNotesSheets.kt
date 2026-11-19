package com.example.readalong.ui.player

import com.example.readalong.domain.i18n.tr
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.R
import com.example.readalong.data.book.BookNotesRepository
import com.example.readalong.domain.model.AiCharacters
import com.example.readalong.domain.model.CharacterEntry
import com.example.readalong.domain.model.NameSuggestion
import com.example.readalong.playback.PlaybackChapter
import com.example.readalong.playback.clockTime
import com.example.readalong.ui.components.ActionSheet
import com.example.readalong.ui.components.SheetDivider
import com.example.readalong.ui.components.SheetRow
import kotlinx.coroutines.launch

private fun notes(context: android.content.Context) = (context.applicationContext as? com.example.readalong.ReadAlongApplication)?.bookNotes

/** The last ten places this book jumped away from, newest first. Tapping one goes back there. */
@Composable
fun HistorySheet(bookId: String, positionMs: Long, chapterTitle: String, seek: (Long) -> Unit, close: () -> Unit) {
    val repository = notes(LocalContext.current) ?: return
    val entries by remember(bookId) { repository.history(bookId) }.collectAsStateWithLifecycle(emptyList())
    ActionSheet(close, "Listening history", if (entries.isEmpty()) null else "Your last ${entries.size} jumps") {
        historyItems(entries, positionMs, chapterTitle, seek, close)
    }
}

/** The book's recent jumps, newest first, under a "Now" row. Shared by the Bookmarks sheet's History tab. */
@Composable
internal fun rememberHistory(bookId: String): State<List<com.example.readalong.domain.model.HistoryEntry>> {
    val repository = notes(LocalContext.current)
    return remember(bookId, repository) { repository?.history(bookId) ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
}

internal fun androidx.compose.foundation.lazy.LazyListScope.historyItems(entries: List<com.example.readalong.domain.model.HistoryEntry>, positionMs: Long, chapterTitle: String,
    seek: (Long) -> Unit, close: () -> Unit) {
    item { SheetRow("Now", {}, icon = R.drawable.ic_headphones, supporting = chapterTitle, meta = clockTime(positionMs), selected = true) }
    if (entries.isEmpty()) item {
        Text(tr("Skips and chapter changes show up here, so you can go back."),
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(entries, key = { "${it.savedAt}:${it.positionMs}" }) { entry ->
        SheetRow(entry.kind.label, { seek(entry.positionMs); close() }, Modifier.testTag("history_entry"), icon = R.drawable.ic_history,
            supporting = listOf(entry.chapterTitle, ago(entry.savedAt)).filter { it.isNotBlank() }.joinToString(" · "),
            meta = clockTime(entry.positionMs))
    }
}

private fun ago(at: Long): String {
    val minutes = (System.currentTimeMillis() - at).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> android.text.format.DateFormat.format("EEE d MMM", at).toString()
    }
}

/**
 * The book's Character List: people the listener adds, each with a note. Tapping someone goes to where
 * they were first heard. Names that keep coming up in the text so far are offered as suggestions.
 */
@Composable
fun CharacterListSheet(bookId: String, positionMs: Long, chapters: List<PlaybackChapter>, seek: (Long) -> Unit, close: () -> Unit, title: String = "", author: String? = null) {
    val repository = notes(LocalContext.current) ?: return
    val scope = rememberCoroutineScope()
    val people by remember(bookId) { repository.characters(bookId) }.collectAsStateWithLifecycle(emptyList())
    var suggestions by remember { mutableStateOf<List<NameSuggestion>>(emptyList()) }
    // Worked out once per opening (and as names are added), from the text heard so far only.
    LaunchedEffect(bookId, people.size) { suggestions = runCatching { repository.suggestions(bookId, positionMs, people.map { it.name }) }.getOrDefault(emptyList()) }
    var editing by remember { mutableStateOf<CharacterEntry?>(null) }
    var askAi by remember { mutableStateOf(false) }
    var chooseHow by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    fun where(ms: Long) = chapters.lastOrNull { it.startMs <= ms }?.title ?: clockTime(ms)
    // One Add character button, pinned at the bottom; it asks whether to type someone in or ask an AI.
    ActionSheet(close, "Character List", when (people.size) { 0 -> null; 1 -> "1 person"; else -> "${people.size} people" }, footer = {
        Button(onClick = { if (title.isNotBlank()) chooseHow = true else editing = CharacterEntry(BookNotesRepository.newId(), "") },
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp).heightIn(min = 48.dp).testTag("add_character")) {
            Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("Add character"))
        }
    }) {
        if (people.isEmpty()) item {
            Text(tr("Keep track of who's who. Add people as you meet them, with a note to jog your memory."),
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(people, key = { it.id }) { person ->
            CharacterRow(person, expanded = expanded == person.id, firstHeard = person.firstMs?.let { "First heard in ${where(it)}" },
                toggle = { expanded = if (expanded == person.id) null else person.id },
                edit = { editing = person }, go = person.firstMs?.let { ms -> { seek(ms); close() } })
        }
        if (suggestions.isNotEmpty()) {
            item { SheetDivider() }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Heard so far"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()).testTag("character_suggestions"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { suggestion ->
                            AssistChip(onClick = { scope.launch { repository.saveCharacter(bookId, CharacterEntry(BookNotesRepository.newId(), suggestion.name, firstMs = suggestion.firstMs)) } },
                                label = { Text(suggestion.name) }, leadingIcon = { Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp)) })
                        }
                    }
                }
            }
        }
    }
    if (chooseHow) AlertDialog(onDismissRequest = { chooseHow = false }, title = { Text(tr("Add characters")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { chooseHow = false; editing = CharacterEntry(BookNotesRepository.newId(), "") },
                Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("add_myself")) {
                Column(Modifier.fillMaxWidth()) { Text(tr("Add someone myself")); Text(tr("Type a name and a note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            OutlinedButton(onClick = { chooseHow = false; askAi = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("make_with_ai")) {
                Column(Modifier.fillMaxWidth()) { Text(tr("Make with AI")); Text(tr("Ask ChatGPT, Gemini or Claude who's who so far"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { chooseHow = false }) { Text(tr("Cancel")) } })
    if (askAi) AiCharacterDialog(AiCharacters.prompt(title, author, chapters.lastOrNull { it.startMs <= positionMs }?.title ?: "the ${clockTime(positionMs)} mark"),
        import = { answer -> repository.importCharacters(bookId, AiCharacters.parse(answer)) }, close = { askAi = false })
    editing?.let { person ->
        var name by remember(person.id) { mutableStateOf(person.name) }
        var note by remember(person.id) { mutableStateOf(person.note) }
        val isNew = people.none { it.id == person.id }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(tr(if (isNew) "Add character" else "Edit character")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text(tr("Name")) }, modifier = Modifier.testTag("character_name"))
                OutlinedTextField(note, { note = it.take(300) }, label = { Text(tr("Note")) }, minLines = 2, maxLines = 4, modifier = Modifier.testTag("character_note"))
                if (!isNew) TextButton(onClick = { scope.launch { repository.removeCharacter(bookId, person.id) }; editing = null }, contentPadding = PaddingValues(0.dp)) {
                    Text(tr("Remove from list"), color = MaterialTheme.colorScheme.error)
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) scope.launch { repository.saveCharacter(bookId, person.copy(name = trimmed, note = note.trim(), firstMs = person.firstMs.takeIf { trimmed.equals(person.name, true) })) }
                editing = null
            }, enabled = name.isNotBlank()) { Text(tr("Save")) }
        }, dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Cancel")) } })
    }
}

/**
 * Ask ChatGPT, Gemini or Claude for the list: the prompt is copied and the assistant opens (with the
 * question filled in where it supports that); the answer is pasted back and read into the list.
 */
@Composable
private fun AiCharacterDialog(prompt: String, import: suspend (String) -> Int, close: () -> Unit) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var answer by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    val query = android.net.Uri.encode(prompt)
    val assistants = listOf("ChatGPT" to "https://chatgpt.com/?q=$query", "Gemini" to "https://gemini.google.com/app", "Claude" to "https://claude.ai/new?q=$query")
    AlertDialog(onDismissRequest = close, title = { Text(tr("Make with AI")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("1. Ask an assistant. The question is copied for you, and only the book's title, author and your chapter are shared."),
                style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                assistants.forEach { (name, url) ->
                    OutlinedButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(prompt))
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, Modifier.weight(1f).testTag("ai_$name"), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(tr(name), maxLines = 1) }
                }
            }
            Text(tr("2. Copy its answer, then paste it here."), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(answer, { answer = it.take(20_000); result = null }, Modifier.fillMaxWidth().testTag("ai_answer"),
                label = { Text(tr("Answer")) }, minLines = 3, maxLines = 6,
                trailingIcon = { TextButton(onClick = { clipboard.getText()?.text?.let { answer = it.take(20_000); result = null } }) { Text(tr("Paste")) } })
            result?.let { Text(tr(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("ai_result")) }
            Text(tr("AI can get details wrong or give things away, so check the list. You can edit or remove anyone."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = {
        TextButton(enabled = answer.isNotBlank(), onClick = {
            scope.launch {
                val added = import(answer)
                if (added > 0) close()
                else result = if (AiCharacters.parse(answer).isEmpty()) "No names found. Paste the list with one person per line, like “Name — who they are”." else "Everyone in that answer is already on your list."
            }
        }, modifier = Modifier.testTag("ai_add")) { Text(tr("Add to list")) }
    }, dismissButton = { TextButton(onClick = close) { Text(tr("Cancel")) } })
}

/**
 * One person: tapping opens the row (with a short size animation) to show their whole note, where they
 * were first heard and a button to go there; tapping again closes it. The pencil edits.
 */
@Composable
private fun CharacterRow(person: CharacterEntry, expanded: Boolean, firstHeard: String?, toggle: () -> Unit, edit: () -> Unit, go: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().animateContentSize(androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))
        .clickable(onClickLabel = if (expanded) "Show less" else "Show more", onClick = toggle)
        .padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp).testTag("character_${person.name}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(person.name, style = MaterialTheme.typography.bodyLarge)
                val summary = person.note.ifBlank { firstHeard ?: "Tap the pencil to add a note" }
                Text(tr(summary), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            IconButton(onClick = edit) { Icon(painterResource(R.drawable.ic_edit), tr("Edit ${person.name}"), Modifier.size(22.dp), tint = colors.onSurfaceVariant) }
        }
        if (expanded && (go != null || (firstHeard != null && person.note.isNotBlank()))) Row(Modifier.fillMaxWidth().padding(top = 6.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (person.note.isNotBlank() && firstHeard != null) Text(firstHeard, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            else Spacer(Modifier.weight(1f))
            if (go != null) TextButton(onClick = go, Modifier.testTag("character_go")) { Text(tr("Go there")) }
        }
    }
}
