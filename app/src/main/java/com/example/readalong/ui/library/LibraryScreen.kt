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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
    /** How far the folder scan under way has got, once its books are counted. */
    scanProgress: com.example.readalong.data.importing.ScanProgress? = null,
    snackbar: SnackbarHostState? = null,
    selectedBookId: String? = null,
    transcribing: Boolean = false,
    onQueue: () -> Unit = {},
    onRetry: () -> Unit,
    onAbout: () -> Unit,
    onFolders: () -> Unit = {},
    onLongBook: (com.example.readalong.domain.model.Book) -> Unit = {},
    onBook: (String) -> Unit,
    /** Each book's place in the folder view, from [com.example.readalong.domain.model.bookPlaces]. */
    bookPlaces: Map<String, List<String>> = emptyMap(),
    miniPlayer: @Composable () -> Unit = {},
    importControls: @Composable () -> Unit = {},
    importStatus: @Composable () -> Unit = {},
    emptyActions: @Composable () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    val settings = com.example.readalong.ui.theme.LocalAppSettings.current
    // Sort order and view are saved, and also held here so they change at once (and in tests without a store).
    var sortName by rememberSaveable(settings.librarySort) { mutableStateOf(settings.librarySort) }
    var folderView by rememberSaveable(settings.folderLibrary) { mutableStateOf(settings.folderLibrary) }
    // The folder being looked at in the folder view, as names joined by new lines ("" is the top).
    var folderPathText by rememberSaveable { mutableStateOf("") }
    val folderPath = if (folderPathText.isEmpty()) emptyList() else folderPathText.split('\n')
    var sortMenu by remember { mutableStateOf(false) }
    val sort = LibrarySort.entries.firstOrNull { it.name == sortName } ?: LibrarySort.RECENT
    // A background rescan shows its progress only once it has taken a second, so quick ones stay quiet.
    var slowSync by remember { mutableStateOf(false) }
    LaunchedEffect(scanningFolder != null) {
        slowSync = false
        if (scanningFolder != null) { kotlinx.coroutines.delay(1_000); slowSync = true }
    }
    val syncProgress = scanProgress?.takeIf { scanningFolder != null && slowSync && !readingFolder }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.example.readalong.ReadAlongApplication
    val scope = rememberCoroutineScope()
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    fun chooseSort(choice: LibrarySort) {
        sortName = choice.name; sortMenu = false
        scope.launch { app?.appPreferences?.update { it.copy(librarySort = choice.name) } }
    }
    fun chooseView(folders: Boolean) {
        folderView = folders; sortMenu = false; folderPathText = ""
        scope.launch { app?.appPreferences?.update { it.copy(folderLibrary = folders) } }
    }
    val sortButton: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { sortMenu = true }, modifier = Modifier.testTag("library_sort")) { Icon(painterResource(R.drawable.ic_sort), "Sort and view", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            SortAndViewMenu(sortMenu, onDismiss = { sortMenu = false }, folderView = folderView, onView = ::chooseView, sort = sort, onSort = ::chooseSort,
                listMode = settings.listLibrary, onListMode = { scope.launch { app?.appPreferences?.update { it.copy(listLibrary = !settings.listLibrary) } }; sortMenu = false })
        }
    }
    val showFolders = folderView && query.isBlank() && state is LibraryUiState.Ready
    val openFolder = if (showFolders) com.example.readalong.domain.model.folderLevel((state as LibraryUiState.Ready).books, bookPlaces, folderPath, sort) else null
    // The folder being looked at is gone (removed, or its books moved): show the top instead.
    val folderGone = folderPath.isNotEmpty() && openFolder != null && openFolder.folders.isEmpty() && openFolder.books.isEmpty()
    val folders = if (folderGone) com.example.readalong.domain.model.folderLevel((state as LibraryUiState.Ready).books, bookPlaces, emptyList(), sort) else openFolder
    LaunchedEffect(folderGone) { if (folderGone) folderPathText = "" }
    // Back steps out of a folder before it leaves the library.
    androidx.activity.compose.BackHandler(enabled = showFolders && folderPath.isNotEmpty()) { folderPathText = folderPath.dropLast(1).joinToString("\n") }
    fun hideTip() { if (!settings.longPressTipHidden) scope.launch { app?.appPreferences?.update { it.copy(longPressTipHidden = true) } } }
    // The header lives in the top bar rather than the grid so it stays pinned while the library scrolls.
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                // The sync bar is drawn over the header's bottom edge, so the header never changes height.
                Box {
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
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { com.example.readalong.ui.launch.LibraryLogo(scanningFolder, transcribing, settings.animateTranscriptionLogo, readingFolder = readingFolder, onClick = onQueue, syncCount = (syncProgress ?: scanProgress?.takeIf { readingFolder })?.count()) }
                        IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) query = "" },
                            modifier = Modifier.onGloballyPositioned { val b = it.boundsInRoot(); iconCenter = b.center }) { Icon(painterResource(R.drawable.ic_search), "Search library") }
                        importControls()
                    }
                    }
                    // Grows out of the search icon and glides back into it, with no bounce either way.
                    com.example.readalong.ui.components.SpringSearchBar(state is LibraryUiState.Ready && searchVisible,
                        anchor = iconCenter?.let { c -> barOrigin?.let { c - it } }, query = query, onQuery = { query = it },
                        onClose = { searchVisible = false; query = "" }, accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().onGloballyPositioned { barOrigin = it.positionInRoot() },
                        placeholder = stringResource(R.string.search_books), testTag = "library_search", topGap = 16.dp)
                }
                syncProgress?.let { SyncBar(it, Modifier.align(Alignment.BottomCenter)) }
                }
            }
        },
        bottomBar = miniPlayer,
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
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
                if (showFolders && state.books.isNotEmpty()) {
                    val level = folders ?: return@LazyVerticalGrid
                    item(key = "folder-header", span = { GridItemSpan(maxLineSpan) }) {
                        FolderHeader(if (folderGone) emptyList() else folderPath, onOpen = { folderPathText = it.joinToString("\n") }, sortButton = sortButton)
                    }
                    items(level.folders, key = { "folder:${it.path.joinToString("/")}" }, span = { GridItemSpan(maxLineSpan) }) { node ->
                        FolderRow(node, Modifier.animateItem(fadeInSpec = tween(180), placementSpec = tween(260, easing = com.example.readalong.ui.reading.ReaderMotionEasing), fadeOutSpec = tween(120))) {
                            folderPathText = node.path.joinToString("\n")
                        }
                    }
                    items(level.books, key = { "book:${it.id}" }) { book -> Box(Modifier.animateItem(fadeInSpec = tween(180), placementSpec = tween(260, easing = com.example.readalong.ui.reading.ReaderMotionEasing), fadeOutSpec = tween(120))) { if (settings.listLibrary) BookListRow(book, selected = book.id == selectedBookId, onLongClick = { hideTip(); onLongBook(book) }) { onBook(book.id) } else BookTile(book, selected = book.id == selectedBookId, onLongClick = { hideTip(); onLongBook(book) }) { onBook(book.id) } } }
                    return@LazyVerticalGrid
                }
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
                            Text("Press and hold a book to change its title, cover or status", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
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
                            if (group == groups.first()) sortButton() else Text(group.books.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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

/** The menu behind the sort button: how the library is laid out, then how books are ordered. */
@Composable
private fun SortAndViewMenu(expanded: Boolean, onDismiss: () -> Unit, folderView: Boolean, onView: (Boolean) -> Unit,
    sort: LibrarySort, onSort: (LibrarySort) -> Unit, listMode: Boolean, onListMode: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    @Composable fun heading(text: String) = Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = muted)
    @Composable fun tick(on: Boolean): (@Composable () -> Unit)? = if (on) ({ Icon(painterResource(R.drawable.ic_check), "Selected", tint = MaterialTheme.colorScheme.primary) }) else null
    @Composable fun lead(icon: Int?): @Composable () -> Unit = { if (icon != null) Icon(painterResource(icon), null, Modifier.size(22.dp), tint = muted) else Spacer(Modifier.size(22.dp)) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.testTag("library_sort_menu")) {
        heading("View")
        DropdownMenuItem(text = { Text("Sections") }, onClick = { onView(false) }, leadingIcon = lead(R.drawable.ic_bookshelf), trailingIcon = tick(!folderView))
        DropdownMenuItem(text = { Text("Folders") }, onClick = { onView(true) }, leadingIcon = lead(R.drawable.ic_folder), trailingIcon = tick(folderView),
            modifier = Modifier.testTag("library_view_folders"))
        DropdownMenuItem(text = { Text(if (listMode) "Cover mode" else "List mode") }, onClick = onListMode, leadingIcon = lead(null))
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        heading("Sort")
        LibrarySort.entries.forEach { choice ->
            DropdownMenuItem(text = { Text(stringResource(when (choice) {
                LibrarySort.RECENT -> R.string.sort_recent
                LibrarySort.TITLE -> R.string.sort_title
                LibrarySort.AUTHOR -> R.string.sort_author
            })) }, onClick = { onSort(choice) }, leadingIcon = lead(null), trailingIcon = tick(choice == sort))
        }
    }
}

/** The folder view's heading: "Folders" at the top; inside a folder, a back button, its name and the path above it. */
@Composable
private fun FolderHeader(path: List<String>, onOpen: (List<String>) -> Unit, sortButton: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("folder_header")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (path.isNotEmpty()) IconButton(onClick = { onOpen(path.dropLast(1)) }, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(painterResource(R.drawable.ic_back), "Up one folder")
            }
            Text(path.lastOrNull() ?: "Folders", Modifier.weight(1f).semantics { heading() }.then(if (path.isNotEmpty()) Modifier.offset(x = (-8).dp) else Modifier),
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            sortButton()
        }
        if (path.size > 1) {
            // Every folder above this one; tap a name to go back to it.
            Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                (listOf("Folders") + path.dropLast(1)).forEachIndexed { index, name ->
                    if (index > 0) Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(name, Modifier.clip(RoundedCornerShape(6.dp)).clickable { onOpen(path.take(index)) }.padding(horizontal = 4.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** One folder in the folder view: up to three of its covers stacked, its name, and how many of its books are at each stage. */
@Composable
private fun FolderRow(node: com.example.readalong.domain.model.FolderNode, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val books = node.books
    val started = books.count { it.section == LibrarySection.CURRENT }
    val finished = books.count { it.section == LibrarySection.FINISHED }
    val fresh = books.size - started - finished
    val summary = buildList {
        add(if (books.size == 1) "1 book" else "${books.size} books")
        if (started > 0) add("$started started")
        if (fresh > 0 && fresh != books.size) add("$fresh not started")
        if (finished > 0) add(if (finished == books.size) "all finished" else "$finished finished")
    }.joinToString(" · ")
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium)).clickable(onClick = onClick)
        .padding(vertical = 6.dp).testTag("folder_row"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(72.dp)) {
            val covers = books.take(3)
            covers.reversed().forEachIndexed { index, book ->
                val step = covers.size - 1 - index
                com.example.readalong.ui.components.BookCover(book, Modifier.size(56.dp).offset(x = (step * 8).dp, y = (16 - step * 8).dp)
                    .graphicsLayer { alpha = 1f - step * .25f }.clip(RoundedCornerShape(com.example.readalong.ui.theme.Corners.small)), showTitle = false)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(node.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
