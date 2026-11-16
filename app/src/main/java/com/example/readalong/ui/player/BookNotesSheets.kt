package com.example.readalong.ui.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
        item { SheetRow("Now", {}, icon = R.drawable.ic_headphones, supporting = chapterTitle, meta = clockTime(positionMs), selected = true) }
        if (entries.isEmpty()) item {
            Text("Skips, chapter changes and other jumps show up here, so you can go back if one was a mistake.",
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(entries, key = { "${it.savedAt}:${it.positionMs}" }) { entry ->
            SheetRow(entry.kind.label, { seek(entry.positionMs); close() }, Modifier.testTag("history_entry"), icon = R.drawable.ic_undo,
                supporting = listOf(entry.chapterTitle, ago(entry.savedAt)).filter { it.isNotBlank() }.joinToString(" · "),
                meta = clockTime(entry.positionMs))
        }
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
fun CharacterListSheet(bookId: String, positionMs: Long, chapters: List<PlaybackChapter>, seek: (Long) -> Unit, close: () -> Unit) {
    val repository = notes(LocalContext.current) ?: return
    val scope = rememberCoroutineScope()
    val people by remember(bookId) { repository.characters(bookId) }.collectAsStateWithLifecycle(emptyList())
    var suggestions by remember { mutableStateOf<List<NameSuggestion>>(emptyList()) }
    // Worked out once per opening (and as names are added), from the text heard so far only.
    LaunchedEffect(bookId, people.size) { suggestions = runCatching { repository.suggestions(bookId, positionMs, people.map { it.name }) }.getOrDefault(emptyList()) }
    var editing by remember { mutableStateOf<CharacterEntry?>(null) }
    fun where(ms: Long) = chapters.lastOrNull { it.startMs <= ms }?.title ?: clockTime(ms)
    ActionSheet(close, "Character List", when (people.size) { 0 -> null; 1 -> "1 person"; else -> "${people.size} people" }, header = {
        Button(onClick = { editing = CharacterEntry(BookNotesRepository.newId(), "") },
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 12.dp).heightIn(min = 48.dp).testTag("add_character")) {
            Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add character")
        }
    }) {
        if (people.isEmpty()) item {
            Text("Keep track of who's who. Add people as you meet them, with a note to jog your memory.",
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(people, key = { it.id }) { person ->
            SheetRow(person.name, { person.firstMs?.let { seek(it); close() } ?: run { editing = person } },
                supporting = person.note.ifBlank { person.firstMs?.let { "First heard in ${where(it)}" } ?: "Tap to add a note" },
                trailing = {
                    IconButton(onClick = { editing = person }) {
                        Icon(painterResource(R.drawable.ic_edit), "Edit ${person.name}", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                })
        }
        if (suggestions.isNotEmpty()) {
            item { SheetDivider() }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Suggested from the text so far", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.horizontalScroll(rememberScrollState()).testTag("character_suggestions"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { suggestion ->
                            AssistChip(onClick = { scope.launch { repository.saveCharacter(bookId, CharacterEntry(BookNotesRepository.newId(), suggestion.name, firstMs = suggestion.firstMs)) } },
                                label = { Text(suggestion.name) }, leadingIcon = { Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp)) })
                        }
                    }
                    Text("Names that come up often, up to where you are. Tap one to add it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    editing?.let { person ->
        var name by remember(person.id) { mutableStateOf(person.name) }
        var note by remember(person.id) { mutableStateOf(person.note) }
        val isNew = people.none { it.id == person.id }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(if (isNew) "Add character" else "Edit character") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.testTag("character_name"))
                OutlinedTextField(note, { note = it.take(300) }, label = { Text("Note") }, minLines = 2, maxLines = 4, modifier = Modifier.testTag("character_note"))
                if (!isNew) TextButton(onClick = { scope.launch { repository.removeCharacter(bookId, person.id) }; editing = null }, contentPadding = PaddingValues(0.dp)) {
                    Text("Remove from list", color = MaterialTheme.colorScheme.error)
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) scope.launch { repository.saveCharacter(bookId, person.copy(name = trimmed, note = note.trim(), firstMs = person.firstMs.takeIf { trimmed.equals(person.name, true) })) }
                editing = null
            }, enabled = name.isNotBlank()) { Text("Save") }
        }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}
