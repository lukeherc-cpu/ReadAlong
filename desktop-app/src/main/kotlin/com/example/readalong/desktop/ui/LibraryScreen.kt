@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.readalong.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.readalong.desktop.core.LibraryBook
import com.example.readalong.domain.model.LibrarySection
import com.example.readalong.domain.model.LibrarySort
import com.example.readalong.domain.model.libraryGroups
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File

@Composable
fun LibraryScreen(app: AppState) {
    val books by app.library.books.collectAsState()
    val scan by app.importer.state.collectAsState()
    val queue by app.transcription.queue.collectAsState()
    val job by app.transcription.job.collectAsState()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(LibrarySort.RECENT) }
    val byId = remember(books) { books.associateBy { it.id } }
    val groups = remember(books, query, sort) { libraryGroups(books.map { it.asBook() }, query, sort) }

    Column(Modifier.fillMaxSize()) {
        // Header: the phone's top bar, icon for icon. Settings and library folders on the left, the logo (which is
        // the queue button and turns its pages while there is work) in the middle, search and add on the right.
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp)) {
            // Where the search icon sits relative to the search bar, so the bar can spring out of it.
            var iconCenter by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
            var barOrigin by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AppIconButton("ic_settings", "Settings", { app.open(Screen.Settings) })
                    FoldersButton(app)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        QueueLogo(scanning = scan.running && !scan.adding, readingFolder = scan.running && scan.adding,
                            transcribing = job?.phase == "Transcribing", queued = queue.size, onClick = { app.open(Screen.Queue) }, size = 52.dp)
                    }
                    AppIconButton("ic_search", "Search library", { searching = !searching; if (!searching) query = "" },
                        Modifier.onGloballyPositioned { iconCenter = it.boundsInRoot().center })
                    AddBookButton(app)
                }
            }
            SpringSearchBar(searching && books.isNotEmpty(), iconCenter?.let { c -> barOrigin?.let { c - it } }, query, { query = it },
                onClose = { searching = false; query = "" }, Modifier.fillMaxWidth().onGloballyPositioned { barOrigin = it.positionInRoot() })
            AnimatedVisibility(scan.running, enter = fadeIn(), exit = fadeOut()) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(scan.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (books.isEmpty()) { EmptyLibrary(app, scan.running); return@Column }
        val grid = rememberLazyGridState()
        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(GridCells.Adaptive(176.dp), Modifier.smoothWheelScroll(grid), state = grid, contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (groups.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("No books match “$query”.", Modifier.padding(top = 40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                groups.forEach { group ->
                    item(key = "section:${group.section}", span = { GridItemSpan(maxLineSpan) }) {
                        SectionTitle(when (group.section) { LibrarySection.CURRENT -> "Current"; LibrarySection.NOT_STARTED -> "Not started"; LibrarySection.FINISHED -> "Finished" },
                            Modifier.animateItem()) {
                            if (group == groups.first()) SortButton(sort) { sort = it }
                            else Text(group.books.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(group.books, key = { it.id }) { item ->
                        val book = byId[item.id] ?: return@items
                        val making = job?.takeIf { it.bookId == book.id }
                        BookTile(app, book, making?.fraction, book.id in queue, Modifier.animateItem(fadeInSpec = tween(220), placementSpec = spring(stiffness = 400f)))
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp, horizontal = 4.dp))
        }
    }
}

/**
 * The phone's search bar: a pill the size of the search icon springs out leftwards from under the icon with a small
 * bounce. Closing has no bounce: the text fades, the bar glides back into a circle under the icon, then the circle
 * lifts into the icon and fades while the space closes up. Focused as soon as it opens, closed with its X or Esc.
 * [anchor] is the icon's centre in this bar's coordinates (null until measured).
 */
@Composable
private fun SpringSearchBar(visible: Boolean, anchor: androidx.compose.ui.geometry.Offset?, query: String, onQuery: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val stretch = remember { Animatable(0f) }
    val lift = remember { Animatable(1f) }
    val fade = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }
    val space = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val ease = CubicBezierEasing(.2f, 0f, .1f, 1f)
    LaunchedEffect(visible) {
        if (visible) {
            closing = false
            if (!shown) { stretch.snapTo(0f); lift.snapTo(1f); fade.snapTo(0f); text.snapTo(0f); space.snapTo(0f) }
            shown = true
            kotlinx.coroutines.coroutineScope {
                launch { space.animateTo(1f, tween(420, easing = ease)) }
                launch { fade.animateTo(1f, tween(60)) }
                launch { lift.animateTo(0f, tween(230, easing = ease)) }
                launch { stretch.animateTo(1f, spring(dampingRatio = .58f, stiffness = 360f)) }
                launch { kotlinx.coroutines.delay(300); text.animateTo(1f, tween(260, easing = ease)) }
                launch { kotlinx.coroutines.delay(60); runCatching { focus.requestFocus() } }
            }
        } else if (shown) {
            closing = true
            kotlinx.coroutines.coroutineScope {
                launch { text.animateTo(0f, tween(140)) }
                stretch.animateTo(0f, tween(250, easing = CubicBezierEasing(.45f, 0f, .2f, 1f)))
                val out = CubicBezierEasing(.4f, 0f, .6f, 1f)
                launch { lift.animateTo(1f, tween(210, easing = out)) }
                launch { fade.animateTo(0f, tween(210, easing = out)) }
                launch { space.animateTo(0f, tween(230, easing = ease)) }
            }
            shown = false; closing = false
        }
    }
    if (!shown) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val barH = with(density) { 48.dp.toPx() }
    val gap = with(density) { 12.dp.toPx() }
    Box(modifier.layout { m, c ->
        val w = c.maxWidth.toFloat()
        val ax = anchor?.x ?: (w - barH / 2)
        val ay = (anchor?.y ?: -barH * .7f) - gap
        val l0 = ax - barH / 2; val r0 = ax + barH / 2
        val st = stretch.value
        val left = l0 * (1f - st); val right = r0 + (w - r0) * st
        val p = m.measure(androidx.compose.ui.unit.Constraints.fixed((right - left).toInt().coerceAtLeast(1), barH.toInt()))
        layout(c.maxWidth, ((barH + gap) * space.value).toInt()) {
            p.placeWithLayer(left.toInt(), gap.toInt()) {
                translationY = (ay - barH / 2) * lift.value * (if (closing) 1f else lift.value)
                val sc = if (closing) 1f - .3f * lift.value else 1f
                scaleX = sc; scaleY = sc; alpha = fade.value
            }
        }
    }.clip(CircleShape).border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 16.dp, end = 4.dp)) {
        Row(Modifier.fillMaxSize().graphicsLayer { alpha = text.value }, verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.get("ic_search"), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search by title or author", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false)
                BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                        if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) { onClose(); true } else false
                    }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
            }
            IconButton(onClose, Modifier.size(40.dp).pointerHoverIcon(PointerIcon.Hand)) {
                Icon(Icons.Rounded.Close, "Close search", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The phone's shelf button: opens the library folders page. */
@Composable
private fun FoldersButton(app: AppState) = AppIconButton("ic_bookshelf", "Library folders", { app.open(Screen.Folders) })

/** The phone's sort button, at the end of the first section's heading. */
@Composable
private fun SortButton(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppIconButton("ic_sort", "Sort", { open = true }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        DropdownMenu(open, { open = false }) {
            listOf(LibrarySort.RECENT to "Recently played", LibrarySort.TITLE to "Title").forEach { (choice, label) ->
                DropdownMenuItem({ Text(label) }, { onSort(choice); open = false },
                    trailingIcon = { if (choice == sort) Icon(Icons.Rounded.Check, "Selected", Modifier.size(18.dp)) })
            }
        }
    }
}

@Composable
private fun BookTile(app: AppState, book: LibraryBook, making: Float?, queued: Boolean, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered) 1f else 0f, spring(dampingRatio = .75f, stiffness = 380f))
    var menu by remember { mutableStateOf(false) }
    ContextMenuArea(items = { bookMenu(app, book) }) {
        Column(modifier.hoverable(interaction).clickable(interaction, null) { app.open(Screen.Player(book.id)) }.pointerHoverIcon(PointerIcon.Hand)) {
            Box {
                BookCover(book, Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer {
                    scaleX = 1f + .035f * lift; scaleY = 1f + .035f * lift; translationY = -6f * lift
                }.shadow((4 + 14 * lift).dp, MaterialTheme.shapes.medium, ambientColor = Color.Black, spotColor = Color.Black))
                // Play straight from the library on hover.
                androidx.compose.animation.AnimatedVisibility(hovered, Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    enter = fadeIn(tween(120)) + scaleIn(initialScale = .7f), exit = fadeOut(tween(100)) + scaleOut(targetScale = .7f)) {
                    Surface(Modifier.size(44.dp).clickable { app.play(book.id) }, shape = CircleShape, color = MaterialTheme.colorScheme.primary, shadowElevation = 6.dp) {
                        Box(contentAlignment = Alignment.Center) { Icon(AppIcons.get("ic_play"), "Play", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                }
                if (making != null || queued) Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = CircleShape, color = Color.Black.copy(alpha = .6f)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.get("ic_waveform"), null, Modifier.size(12.dp), tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text(if (making != null) "${(making * 100).toInt()}%" else "Queued", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(book.author, if (book.progress in .005f..0.995f && !book.finished) "${(book.progress * 100).toInt()}%" else null).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun bookMenu(app: AppState, book: LibraryBook): List<ContextMenuItem> = listOfNotNull(
    ContextMenuItem("Play") { app.play(book.id) },
    ContextMenuItem("Open") { app.open(Screen.Player(book.id)) },
    if (app.transcripts.info(book.id)?.complete == true) null else ContextMenuItem("Make ReadAlong now") { app.transcription.enqueue(book.id, now = true) },
    ContextMenuItem("Match an EPUB…") { chooseFile("Choose the book's EPUB", "epub")?.let { f -> app.library.update(book.id) { it.copy(epubPath = f.path, textSource = "EPUB") } } },
    ContextMenuItem("Import a .readalong transcript…") {
        chooseFile("Choose a ReadAlong transcript", "readalong")?.let { f ->
            app.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { app.transcripts.importPackage(book, f) }
                    .onSuccess { app.messages.emit("Transcript imported for ${book.title}.") }
                    .onFailure { app.messages.emit(it.message ?: "That transcript couldn't be imported.") }
            }
        }
    },
    if (app.transcripts.info(book.id)?.complete == true) ContextMenuItem("Save .readalong for the phone…") {
        chooseSave("Save the ReadAlong", app.transcription.packageFile(book))?.let { f ->
            app.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (app.transcription.savePackage(book, f)) app.messages.emit("Saved ${f.name}. Put it beside the audiobook on your phone and rescan.")
            }
        }
    } else null,
    ContextMenuItem(if (book.finished) "Mark as not finished" else "Mark as finished") { app.library.update(book.id) { it.copy(finished = !it.finished, positionMs = if (it.finished) 0 else it.positionMs) } },
    book.folder?.let { folder -> ContextMenuItem("Show in folder") { runCatching { Desktop.getDesktop().open(File(folder)) } } },
    ContextMenuItem("Remove from library") { if (app.player.state.value.bookId == book.id) app.player.close(); app.transcription.remove(book.id); app.library.remove(book.id) },
)

@Composable
private fun EmptyLibrary(app: AppState, scanning: Boolean) = LibrarySetup(app, scanning)
