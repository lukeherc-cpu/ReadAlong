package com.example.readalong.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
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
import com.example.readalong.ui.components.smoothWheelScroll

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    scanningFolder: String? = null,
    readingFolder: Boolean = false,
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
    // The header lives in the top bar rather than the grid so it stays pinned while the library scrolls.
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .testTag("library_header"),
                ) {
                    // Where the search icon sits relative to the search bar, so the bar can spring out of it.
                    var iconCenter by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
                    var barOrigin by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onAbout) { Icon(painterResource(R.drawable.ic_settings), "Settings") }
                        IconButton(onClick = onFolders) { Icon(painterResource(R.drawable.ic_bookshelf), "Audiobook folders") }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { com.example.readalong.ui.launch.LibraryLogo(scanningFolder, transcribing, settings.animateTranscriptionLogo, readingFolder = readingFolder, onClick = onQueue) }
                        IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) query = "" },
                            modifier = Modifier.onGloballyPositioned { val b = it.boundsInRoot(); iconCenter = b.center }) { Icon(painterResource(R.drawable.ic_search), "Search library") }
                        importControls()
                    }
                    }
                    // Springs out of the search icon with a small bounce, and glides back into it without one.
                    com.example.readalong.ui.components.SpringSearchBar(state is LibraryUiState.Ready && searchVisible,
                        anchor = iconCenter?.let { c -> barOrigin?.let { c - it } }, query = query, onQuery = { query = it },
                        onClose = { searchVisible = false; query = "" }, accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().onGloballyPositioned { barOrigin = it.positionInRoot() },
                        placeholder = stringResource(R.string.search_books), testTag = "library_search", topGap = 16.dp)
                }
            }
        },
        bottomBar = miniPlayer,
    ) { insets ->
        val libraryGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        LazyVerticalGrid(
            columns = if (settings.listLibrary) GridCells.Fixed(1) else if (width >= 600) GridCells.Adaptive(180.dp) else GridCells.Fixed(2),
            state = libraryGrid,
            modifier = Modifier.fillMaxSize().padding(insets).smoothWheelScroll(libraryGrid).testTag("library_grid"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state is LibraryUiState.Ready) {
                item(key = "import-status", span = { GridItemSpan(maxLineSpan) }) { importStatus() }
                val groups = libraryGroups(state.books, query, sort)
                if (searchVisible && query.isNotBlank() && groups.isNotEmpty()) item(key = "search-count", span = { GridItemSpan(maxLineSpan) }) {
                    com.example.readalong.ui.components.SearchCountLabel(groups.sumOf { it.books.size })
                }
                // A newly chosen library folder is being read: placeholders until (and while) its books arrive.
                val loadingFolder = readingFolder && query.isBlank()
                if (groups.isEmpty() && loadingFolder) {
                    item(key = "skeleton-heading", span = { GridItemSpan(maxLineSpan) }) { SkeletonHeading() }
                    items(6, key = { "skeleton:$it" }) { LibrarySkeletonTile(settings.listLibrary) }
                } else if (groups.isEmpty()) {
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
                    Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium), color = MaterialTheme.colorScheme.secondaryContainer) {
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
                                IconButton(onClick = { sortMenu = true }, modifier = Modifier.testTag("library_sort")) { Icon(painterResource(R.drawable.ic_sort), "Sort and view", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    DropdownMenuItem(text = { Text(if (settings.listLibrary) "Cover mode" else "List mode") }, onClick = { scope.launch { app?.appPreferences?.update { it.copy(listLibrary = !settings.listLibrary) } }; sortMenu = false })
                                    LibrarySort.entries.forEach { choice -> DropdownMenuItem(text = { Text(stringResource(if (choice == LibrarySort.RECENT) R.string.sort_recent else R.string.sort_title)) }, onClick = { sortName = choice.name; sortMenu = false }) }
                                }
                            } else Text(group.books.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(group.books, key = { "book:${it.id}" }) { book -> Box(Modifier.animateItem(fadeInSpec = tween(180), placementSpec = tween(260, easing = com.example.readalong.ui.reading.ReaderMotionEasing), fadeOutSpec = tween(120))) { if (settings.listLibrary) BookListRow(book, selected = book.id == selectedBookId, onLongClick = { hideTip(); onLongBook(book) }) { onBook(book.id) } else BookTile(book, selected = book.id == selectedBookId, onLongClick = { hideTip(); onLongBook(book) }) { onBook(book.id) } } }
                }
                if (groups.isNotEmpty() && loadingFolder) items(2, key = { "skeleton-more:$it" }) { LibrarySkeletonTile(settings.listLibrary) }
            } else if (state == LibraryUiState.Loading) {
                // Placeholder tiles while the library loads, shaped like the real ones.
                item(key = "skeleton-heading", span = { GridItemSpan(maxLineSpan) }) { SkeletonHeading() }
                items(6, key = { "skeleton:$it" }) { LibrarySkeletonTile(settings.listLibrary) }
            } else {
                item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.library_error), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
                    }
                }
            }
        }
    }

}

@Composable
private fun LibraryMessage(title: String, message: String, actions: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = if (actions != null) 24.dp else 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The empty library leads with its words and buttons; only "no results" keeps a picture.
        if (actions == null) com.example.readalong.ui.components.ReadAlongArtwork(R.drawable.art_book, Modifier.size(120.dp))
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

@Composable
private fun shimmer() = com.example.readalong.ui.components.shimmerBrush()

@Composable
private fun SkeletonHeading() {
    val brush = shimmer()
    Box(Modifier.padding(top = 4.dp).fillMaxWidth(.42f).height(22.dp).clip(RoundedCornerShape(11.dp)).background(brush))
}

@Composable
private fun LibrarySkeletonTile(list: Boolean = false) {
    val brush = shimmer()
    if (list) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(com.example.readalong.ui.theme.Corners.small)).background(brush))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(.7f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(brush))
            Box(Modifier.fillMaxWidth(.45f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
        }
    } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium)).background(brush))
        Box(Modifier.fillMaxWidth(.82f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(brush))
        Box(Modifier.fillMaxWidth(.52f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
    }
}
