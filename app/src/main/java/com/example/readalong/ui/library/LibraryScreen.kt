package com.example.readalong.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.readalong.R
import com.example.readalong.domain.model.LibrarySection
import com.example.readalong.domain.model.LibrarySort
import com.example.readalong.domain.model.libraryGroups
import com.example.readalong.ui.theme.ReadAlongTheme

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    scanningFolder: String? = null,
    selectedBookId: String? = null,
    transcribing: Boolean = false,
    onQueue: () -> Unit = {},
    onRetry: () -> Unit,
    onAbout: () -> Unit,
    onFolders: () -> Unit = {},
    onLongBook: (com.example.readalong.domain.model.Book) -> Unit = {},
    onBook: (String) -> Unit,
    miniPlayer: @Composable () -> Unit = {},
    importControls: @Composable () -> Unit = {},
    importStatus: @Composable () -> Unit = {},
    emptyActions: @Composable () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(LibrarySort.RECENT.name) }
    var sortMenu by remember { mutableStateOf(false) }
    val sort = LibrarySort.valueOf(sortName)
    val settings = com.example.readalong.ui.theme.LocalAppSettings.current
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.example.readalong.ReadAlongApplication
    val scope = rememberCoroutineScope()
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    fun hideTip() { if (!settings.longPressTipHidden) scope.launch { app?.appPreferences?.update { it.copy(longPressTipHidden = true) } } }
    Scaffold(bottomBar = miniPlayer) { insets ->
        LazyVerticalGrid(
            columns = if (settings.listLibrary) GridCells.Fixed(1) else if (width >= 600) GridCells.Adaptive(180.dp) else GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(insets).testTag("library_grid"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "toolbar", span = { GridItemSpan(maxLineSpan) }) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onAbout) { Icon(painterResource(R.drawable.ic_settings), "Settings") }
                    IconButton(onClick = onFolders) { Icon(painterResource(R.drawable.ic_bookshelf), "Audiobook folders") }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { com.example.readalong.ui.launch.LibraryLogo(scanningFolder, transcribing, settings.animateTranscriptionLogo, onQueue) }
                    IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) query = "" }) { Icon(painterResource(R.drawable.ic_search), "Search library") }
                    importControls()

                }
                }
            }
            if (state is LibraryUiState.Ready) {
                if (searchVisible) item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                    // Same search look as the reader: accent outline, grows in, count of matching books below.
                    com.example.readalong.ui.components.ReadAlongSearchField(query, { query = it },
                        onClose = { searchVisible = false; query = "" }, accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(), placeholder = stringResource(R.string.search_books), testTag = "library_search")
                }
                item(key = "import-status", span = { GridItemSpan(maxLineSpan) }) { importStatus() }
                val groups = libraryGroups(state.books, query, sort)
                if (searchVisible && query.isNotBlank() && groups.isNotEmpty()) item(key = "search-count", span = { GridItemSpan(maxLineSpan) }) {
                    com.example.readalong.ui.components.SearchCountLabel(groups.sumOf { it.books.size })
                }
                if (groups.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        LibraryMessage(
                            title = stringResource(if (state.books.isEmpty()) R.string.no_audiobooks else R.string.no_search_results),
                            message = stringResource(if (state.books.isEmpty()) R.string.empty_library_message else R.string.no_search_results_message),
                            actions = if (state.books.isEmpty() && query.isBlank()) emptyActions else null,
                        )
                    }
                }
                // One-time hint for the long-press menu; it also goes away the first time a book is long-pressed.
                if (groups.isNotEmpty() && !settings.longPressTipHidden && !searchVisible) item(key = "long-press-tip", span = { GridItemSpan(maxLineSpan) }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Tip: press and hold a book to change its title, cover or status.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                            IconButton(onClick = { scope.launch { app?.appPreferences?.update { it.copy(longPressTipHidden = true) } } }) {
                                com.example.readalong.ui.components.CloseGlyph(MaterialTheme.colorScheme.onSecondaryContainer, "Dismiss tip")
                            }
                        }
                    }
                }
                groups.forEach { group ->
                    item(key = "section:${group.section}", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(when (group.section) {
                                    LibrarySection.CURRENT -> R.string.section_current
                                    LibrarySection.NOT_STARTED -> R.string.section_not_started
                                    LibrarySection.FINISHED -> R.string.section_finished
                                }),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() },
                            )
                            if (group == groups.first()) Box {
                                IconButton(onClick = { sortMenu = true }, modifier = Modifier.testTag("library_sort")) { Icon(painterResource(R.drawable.ic_sort), "Sort and view", tint = MaterialTheme.colorScheme.primary) }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    DropdownMenuItem(text = { Text(if (settings.listLibrary) "Cover mode" else "List mode") }, onClick = { scope.launch { app?.appPreferences?.update { it.copy(listLibrary = !settings.listLibrary) } }; sortMenu = false })
                                    LibrarySort.entries.forEach { choice -> DropdownMenuItem(text = { Text(stringResource(if (choice == LibrarySort.RECENT) R.string.sort_recent else R.string.sort_title)) }, onClick = { sortName = choice.name; sortMenu = false }) }
                                }
                            } else Text(group.books.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    items(group.books, key = { "book:${it.id}" }) { book -> Box(Modifier.animateItem(fadeInSpec = tween(180), placementSpec = tween(260, easing = com.example.readalong.ui.reading.ReaderMotionEasing), fadeOutSpec = tween(120))) { if (settings.listLibrary) BookListRow(book, selected = book.id == selectedBookId, onLongClick = { hideTip(); onLongBook(book) }) { onBook(book.id) } else BookTile(book, selected = book.id == selectedBookId, onLongClick = { hideTip(); onLongBook(book) }) { onBook(book.id) } } }
                }
            } else {
                item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state == LibraryUiState.Loading) {
                            CircularProgressIndicator()
                        } else {
                            Text(stringResource(R.string.library_error), textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun LibraryMessage(title: String, message: String, actions: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = if (actions != null) 24.dp else 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(R.drawable.ic_book), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        actions?.invoke()
    }
}

@Preview(showBackground = true)
@Composable
private fun LibraryPreview() {
    ReadAlongTheme { LibraryScreen(state = LibraryUiState.Ready(emptyList()), onRetry = {}, onAbout = {}, onBook = {}) }
}
