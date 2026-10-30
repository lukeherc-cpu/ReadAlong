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
        // Header: the phone's layout, spread across a window.
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            // As on the phone, the logo is the queue button and turns its pages while there is work.
            QueueLogo(scanning = scan.running && !scan.adding, readingFolder = scan.running && scan.adding,
                transcribing = job?.phase == "Transcribing", queued = queue.size, onClick = { app.open(Screen.Queue) })
            Spacer(Modifier.width(6.dp))
            Text("ReadAlong", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(28.dp))
            SearchField(query, { query = it }, searching, { searching = it }, Modifier.weight(1f).widthIn(max = 520.dp))
            Spacer(Modifier.weight(.01f))
            AnimatedVisibility(scan.running, enter = fadeIn(), exit = fadeOut()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(scan.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
                }
            }
            AppIconButton("ic_sort", if (sort == LibrarySort.RECENT) "Sorted by recent. Sort by title" else "Sorted by title. Sort by recent",
                { sort = if (sort == LibrarySort.RECENT) LibrarySort.TITLE else LibrarySort.RECENT })
            AppIconButton("ic_settings", "Settings", { app.open(Screen.Settings) })
            Spacer(Modifier.width(8.dp))
            PillButton("Add books", { chooseFolder()?.let { app.addPaths(listOf(it)) } }, icon = "ic_add")
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
                            Modifier.animateItem())
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

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, expanded: Boolean, onExpanded: (Boolean) -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (hovered || expanded || query.isNotEmpty()) 1f else .6f))
    Row(modifier.height(44.dp).clip(CircleShape).background(background).hoverable(interaction)
        .clickable(interaction, null) { onExpanded(true); runCatching { focus.requestFocus() } }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.get("ic_search"), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search by title or author", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f), style = MaterialTheme.typography.bodyMedium)
            BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
        }
        if (query.isNotEmpty()) AppIconButton("ic_back", "Clear search", { onQuery(""); onExpanded(false) }, size = 28.dp, iconSize = 16.dp)
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
private fun EmptyLibrary(app: AppState, scanning: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 460.dp)) {
            Icon(AppIcons.get("ic_bookshelf"), null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text(if (scanning) "Looking for audiobooks…" else "Add your audiobooks", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Text("Choose the folder with your audiobooks, or drop files and folders anywhere in this window. ReadAlong finds each book, " +
                "its chapters and cover, and any EPUB beside it.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            PillButton("Choose audiobook folder", { chooseFolder()?.let { app.addPaths(listOf(it)) } }, icon = "ic_folder")
        }
    }
}
