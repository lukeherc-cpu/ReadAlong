package com.example.readalong.ui.library

import com.example.readalong.domain.i18n.tr
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
    // The collection open in the Collections view (null for the list of them, LOOSE for "Not in a collection").
    var openCollection by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<com.example.readalong.domain.model.BookCollection?>(null) }
    var deleting by remember { mutableStateOf<com.example.readalong.domain.model.BookCollection?>(null) }
    var pickingId by rememberSaveable { mutableStateOf<String?>(null) }
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
    val collections by remember(app) { app?.collections?.all ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsState(emptyList())
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    fun chooseSort(choice: LibrarySort) {
        sortName = choice.name; sortMenu = false
        scope.launch { app?.appPreferences?.update { it.copy(librarySort = choice.name) } }
    }
    fun chooseView(folders: Boolean) {
        folderView = folders; sortMenu = false; openCollection = null
        scope.launch { app?.appPreferences?.update { it.copy(folderLibrary = folders) } }
    }
    val sortButton: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { sortMenu = true }, modifier = Modifier.testTag("library_sort")) { Icon(painterResource(R.drawable.ic_sort), tr("Sort and view"), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            SortAndViewMenu(sortMenu, onDismiss = { sortMenu = false }, collectionView = folderView, onView = ::chooseView, sort = sort, onSort = ::chooseSort,
                listMode = settings.listLibrary, onList = { list -> if (list != settings.listLibrary) scope.launch { app?.appPreferences?.update { it.copy(listLibrary = list) } } })
        }
    }
    val showCollections = folderView && query.isBlank() && state is LibraryUiState.Ready
    val collectionRows = if (showCollections) com.example.readalong.domain.model.collectionRows(collections, (state as LibraryUiState.Ready).books, sort) else emptyList()
    val openRow = openCollection?.let { id -> collectionRows.firstOrNull { (it.collection?.id ?: LOOSE) == id } }
    // The collection being looked at was deleted, or its last loose book was filed: show the list instead.
    LaunchedEffect(showCollections, openCollection, openRow == null) { if (showCollections && openCollection != null && openRow == null) openCollection = null }
    // Back steps out of a collection before it leaves the library.
    androidx.activity.compose.BackHandler(enabled = showCollections && openCollection != null) { openCollection = null }
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
                        IconButton(onClick = onAbout) { Icon(painterResource(R.drawable.ic_settings), tr("Settings")) }
                        IconButton(onClick = onFolders) { Icon(painterResource(R.drawable.ic_bookshelf), tr("Audiobook folders")) }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { com.example.readalong.ui.launch.LibraryLogo(scanningFolder, transcribing, settings.animateTranscriptionLogo, readingFolder = readingFolder, onClick = onQueue, syncCount = (syncProgress ?: scanProgress?.takeIf { readingFolder })?.count()) }
                        IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) query = "" },
                            modifier = Modifier.onGloballyPositioned { val b = it.boundsInRoot(); iconCenter = b.center }) { Icon(painterResource(R.drawable.ic_search), tr("Search library")) }
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
        floatingActionButton = {
            if (showCollections && openCollection == null && app != null) ExtendedFloatingActionButton(onClick = { naming = true },
                icon = { Icon(painterResource(R.drawable.ic_add), null) }, text = { Text(tr("New collection")) }, modifier = Modifier.testTag("new_collection"))
        },
        floatingActionButtonPosition = FabPosition.Center,
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
                if (showCollections && state.books.isNotEmpty()) {
                    val row = openRow
                    if (row == null) {
                        item(key = "collections-header", span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(tr("Collections"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                                    sortButton()
                                }
                            }
                        }
                        items(collectionRows, key = { "collection:${it.collection?.id ?: LOOSE}" }, span = { GridItemSpan(maxLineSpan) }) { item ->
                            CollectionRowView(item, Modifier.animateItem(fadeInSpec = tween(180), placementSpec = tween(260, easing = com.example.readalong.ui.reading.ReaderMotionEasing), fadeOutSpec = tween(120))) { openCollection = item.collection?.id ?: LOOSE }
                        }
                        // Room above the New collection button, so it never covers the last row.
                        item(key = "collections-end", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(64.dp)) }
                    } else {
                        val collection = row.collection
                        item(key = "collection-header", span = { GridItemSpan(maxLineSpan) }) {
                            CollectionHeader(row, onBack = { openCollection = null },
                                onRename = collection?.let { { renaming = it } }, onDelete = collection?.let { { deleting = it } })
                        }
                        items(row.books, key = { "book:${it.id}" }) { book -> Box(Modifier.animateItem(fadeInSpec = tween(180), placementSpec = tween(260, easing = com.example.readalong.ui.reading.ReaderMotionEasing), fadeOutSpec = tween(120))) { if (settings.listLibrary) BookListRow(book, selected = book.id == selectedBookId, onLongClick = { hideTip(); onLongBook(book) }) { onBook(book.id) } else BookTile(book, selected = book.id == selectedBookId, onLongClick = { hideTip(); onLongBook(book) }) { onBook(book.id) } } }
                        if (collection != null) item(key = "collection-add") { AddBooksTile(settings.listLibrary) { pickingId = collection.id } }
                    }
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
                            Text(tr("Press and hold a book to change its title, cover or status"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
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
                                    LibrarySection.NEW -> R.string.section_new
                                    LibrarySection.NOT_STARTED -> R.string.section_not_started
                                    LibrarySection.FINISHED -> R.string.section_finished
                                }),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() },
                            )
                            if (group == groups.first()) sortButton() else Text(tr(group.books.size.toString()), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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
    val store = app?.collections
    if (naming && store != null) CollectionNameDialog("New collection", confirm = "Next", message = "Give it a name. You can add books next.", onDismiss = { naming = false }) { name ->
        naming = false
        scope.launch { runCatching { store.create(name) }.onSuccess { pickingId = it.id } }
    }
    renaming?.let { collection -> if (store != null) CollectionNameDialog("Rename collection", initial = collection.name, confirm = "Save", onDismiss = { renaming = null }) { name ->
        renaming = null; scope.launch { store.rename(collection, name) }
    } }
    deleting?.let { collection -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(tr("Delete “${collection.name}”?")) },
        text = { Text(tr("The collection goes away. Its books stay in your library.")) },
        confirmButton = { TextButton(onClick = { deleting = null; openCollection = null; store?.let { scope.launch { it.delete(collection) } } }, modifier = Modifier.testTag("collection_delete_confirm")) {
            Text(tr("Delete collection"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Cancel")) } }) }
    // The picker waits for the new collection to arrive from the store, then shows.
    val picking = pickingId?.let { id -> collections.firstOrNull { it.id == id } }
    if (picking != null && state is LibraryUiState.Ready && store != null) CollectionBookPicker(picking, state.books.sortedWith(com.example.readalong.domain.model.libraryOrder(sort)),
        onDismiss = { pickingId = null }) { ids ->
        pickingId = null; openCollection = picking.id
        scope.launch { store.setBooks(picking, ids) }
    }
}

private const val LOOSE = "\u0000loose"

/** The menu behind the sort button: how the library is laid out, then how books are ordered. */
@Composable
private fun SortAndViewMenu(expanded: Boolean, onDismiss: () -> Unit, collectionView: Boolean, onView: (Boolean) -> Unit,
    sort: LibrarySort, onSort: (LibrarySort) -> Unit, listMode: Boolean, onList: (Boolean) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    @Composable fun heading(text: String) = Text(tr(text), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = muted)
    @Composable fun tick(on: Boolean): (@Composable () -> Unit)? = if (on) ({ Icon(painterResource(R.drawable.ic_check), tr("Selected"), tint = MaterialTheme.colorScheme.primary) }) else null
    @Composable fun lead(icon: Int?): @Composable () -> Unit = { if (icon != null) Icon(painterResource(icon), null, Modifier.size(22.dp), tint = muted) else Spacer(Modifier.size(22.dp)) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.testTag("library_sort_menu")) {
        heading("View")
        // Three layouts, one ticked: covers by section, a list by section, or collections.
        DropdownMenuItem(text = { Text(tr("Cover art")) }, onClick = { onList(false); onView(false) }, leadingIcon = lead(R.drawable.ic_view_covers),
            trailingIcon = tick(!collectionView && !listMode), modifier = Modifier.testTag("library_view_covers"))
        DropdownMenuItem(text = { Text(tr("List")) }, onClick = { onList(true); onView(false) }, leadingIcon = lead(R.drawable.ic_view_list),
            trailingIcon = tick(!collectionView && listMode), modifier = Modifier.testTag("library_view_list"))
        DropdownMenuItem(text = { Text(tr("Collections")) }, onClick = { onView(true) }, leadingIcon = lead(R.drawable.ic_book), trailingIcon = tick(collectionView),
            modifier = Modifier.testTag("library_view_collections"))
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

@Composable
private fun LibraryMessage(title: String, message: String, actions: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = if (actions != null) 24.dp else 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The empty library leads with its words and buttons; only "no results" keeps a picture.
        if (actions == null) com.example.readalong.ui.components.ReadAlongArtwork(R.drawable.art_book, Modifier.size(120.dp))
        Text(tr(title), style = MaterialTheme.typography.titleLarge)
        Text(tr(message), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
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
