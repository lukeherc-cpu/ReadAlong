package com.example.readalong.ui.importing

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.layout.layout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import com.example.readalong.ui.library.remaining
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import com.example.readalong.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.data.importing.FolderMode
import com.example.readalong.data.importing.SCAN_INCOMPLETE
import com.example.readalong.data.local.BookBadges
import com.example.readalong.data.local.FolderEntity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.example.readalong.domain.model.Book
import com.example.readalong.ui.components.BookCover

/** Covers shown on a folder card before the rest are counted as "+N". */
private const val STRIP = 5

/**
 * Library folders: one card per folder with its covers, book count and last check. Pulling down
 * checks every folder; a folder ReadAlong can no longer read asks to be chosen again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(model: ImportViewModel, onBack: () -> Unit) {
    val folders by model.folders.collectAsStateWithLifecycle()
    val folderBooks by model.folderBooks.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val scanningFolder by model.scanningFolder.collectAsStateWithLifecycle()
    val scanProgress by model.scanProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val app = context.applicationContext as? com.example.readalong.ReadAlongApplication
    val library by remember { app?.bookRepository?.observeBooks() ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val booksById = remember(library) { library.associateBy { it.id } }
    val badgeRows by remember { app?.database?.bookDao()?.observeBadges() ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val badges = remember(badgeRows) { badgeRows.associateBy { it.bookId } }
    var removing by remember { mutableStateOf<FolderEntity?>(null) }
    var reconnecting by remember { mutableStateOf<FolderEntity?>(null) }
    var audible by rememberSaveable { mutableStateOf(false) }
    val add = rememberAddAudiobooks(model, "folders")
    val chooseAgain = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val folder = reconnecting
        reconnecting = null
        if (uri != null && folder != null) model.reconnect(folder, uri)
    }
    // Access can be taken away in Android's settings or by removing an SD card: check on every return.
    // Read again when the folder list changes too: a folder just added from here is saved after this screen resumed.
    var granted by remember { mutableStateOf(grantedFolders(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = grantedFolders(context) }
    LaunchedEffect(folders) { granted = grantedFolders(context) }
    val shown = folders.filter { it.mode == FolderMode.LIBRARY.name }

    Scaffold { insets ->
        // The circle shows only while being pulled; the folder card's own bar shows the check itself.
        PullToRefreshBox(isRefreshing = false, onRefresh = model::refreshLibrary, modifier = Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(painterResource(R.drawable.ic_back), "Back to library")
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Library folders", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                            Text(if (shown.isEmpty()) "Add a folder and ReadAlong keeps checking it for new books. Your audio stays where it is."
                                else "Pull down to check every folder for new books", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                    items(shown, key = { it.uri }) { folder ->
                        val ids = folderBooks[folder.uri].orEmpty()
                        val covers = ids.mapNotNull(booksById::get).sortedBy { it.title.lowercase() }
                        val scanning = scanningFolder == folder.name || (busy && folder.error == SCAN_INCOMPLETE)
                        FolderCard(folder, covers, ids.size, badges, scanning = scanning,
                            progress = scanProgress?.takeIf { scanning && it.folder == folder.name },
                            // A folder being scanned is readable, whatever the last permission check said.
                            lostAccess = !scanning && folder.uri !in granted && !com.example.readalong.data.importing.OwnFolderProvider.isOwn(context, folder.uri), enabled = !busy,
                            onCheck = { model.rescan(folder) }, onRemove = { removing = folder },
                            onChooseAgain = { reconnecting = folder; chooseAgain.launch(Uri.parse(folder.uri)) },
                            modifier = Modifier.animateItem())
                    }
                }
                message?.takeIf { scanningFolder == null }?.let {
                    Text(it, Modifier.fillMaxWidth().padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = add, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("choose_folder"), shape = CircleShape) {
                        Icon(painterResource(R.drawable.ic_add), null, Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Add audiobooks", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = { audible = true }, enabled = !busy) { Text("Import from Audible") }
                }
            }
        }
    }
    if (audible) com.example.readalong.ui.audible.AudibleImportEntry(onChooseFolder = { audible = false; add() }, onDismiss = { audible = false })
    removing?.let { folder ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove this folder?") },
            text = { Text("Are you sure? Its books leave your library, along with their ReadAlongs and listening progress. The audio files stay on your phone.") },
            confirmButton = { TextButton(onClick = { model.remove(folder); removing = null }) { Text("Remove folder", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun FolderCard(folder: FolderEntity, covers: List<Book>, count: Int, badges: Map<String, BookBadges>, scanning: Boolean,
    progress: com.example.readalong.data.importing.ScanProgress?, lostAccess: Boolean, enabled: Boolean,
    onCheck: () -> Unit, onRemove: () -> Unit, onChooseAgain: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(folder.name, style = MaterialTheme.typography.titleLarge)
                    when {
                        lostAccess -> Text("ReadAlong lost access to this folder", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        scanning && progress != null -> Text("Reading books… ${progress.remaining()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        scanning -> Text("Looking for books… found $count so far", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        else -> Text(summary(count, folder.lastScanAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (lostAccess) FilledTonalButton(onClick = onChooseAgain, enabled = enabled) { Text("Choose again") }
                else FolderMenu(folder.name, enabled && !scanning, onCheck, onRemove)
            }
            // A scan that stopped part way keeps its message; other problems (an empty folder, unreadable files) show here too.
            if (!lostAccess && !scanning) folder.error?.takeIf { it != SCAN_INCOMPLETE }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            // The same bar as the library's Reading “…” line: it fills once the books are counted.
            if (scanning) com.example.readalong.ui.library.FolderProgressBar(com.example.readalong.ui.library.animatedScanFraction(progress))
            if (!lostAccess && covers.isNotEmpty()) {
                CoverStrip(covers, count, badges)
                BadgeKey()
            }
        }
    }
}

@Composable
private fun FolderMenu(name: String, enabled: Boolean, onCheck: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(onClick = { open = true }, enabled = enabled, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Icon(painterResource(R.drawable.ic_more), "Options for $name")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(16.dp)) {
            DropdownMenuItem(text = { Text("Check for new books") }, onClick = { open = false; onCheck() })
            DropdownMenuItem(text = { Text("Remove folder", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; onRemove() })
        }
    }
}

/**
 * Overlapping square covers; each new one pops in as a scan finds it. Tapping "+N" spreads the covers apart
 * and the rest pop in after them, row by row, while the card grows; "Less" folds them back.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CoverStrip(covers: List<Book>, count: Int, badges: Map<String, BookBadges>) {
    val surface = MaterialTheme.colorScheme.surface
    var expanded by rememberSaveable { mutableStateOf(false) }
    val canExpand = covers.size > STRIP
    LaunchedEffect(canExpand) { if (!canExpand) expanded = false }
    val spread by animateFloatAsState(if (expanded) 1f else 0f, spring(dampingRatio = .85f, stiffness = 380f), label = "Spread covers")
    val shown = if (expanded) covers else covers.take(STRIP)
    val extra = count - covers.take(STRIP).size
    val tile = Modifier.size(56.dp)
    // Each cover reports itself 20dp narrower when folded, so the next one sits over it (12dp overlap after the 8dp gap).
    fun Modifier.overlap(): Modifier = layout { m, c ->
        val p = m.measure(c)
        val w = (p.width - 20.dp.toPx() * (1f - spread)).toInt()
        layout(w, p.height) { p.place(0, 0) }
    }
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = .9f, stiffness = 320f))
            .semantics { contentDescription = "$count books" },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shown.forEachIndexed { index, book ->
            key(book.id) {
                // Starts hidden and shows on the first frame, so a cover found by a scan, or revealed by "+N", pops in.
                val seen = remember { MutableTransitionState(false).apply { targetState = true } }
                val delay = if (index < STRIP) 0 else 40 + 22 * (index - STRIP).coerceAtMost(20)
                AnimatedVisibility(seen, Modifier.overlap(),
                    enter = fadeIn(tween(200, delay)) + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 500f), initialScale = .6f)) {
                    Box {
                        Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(2.dp, surface)) {
                            BookCover(book, Modifier.size(52.dp), showTitle = false)
                        }
                        CoverBadges(badges[book.id], Modifier.align(Alignment.BottomEnd))
                    }
                }
            }
        }
        if (extra > 0 && canExpand || expanded) key(if (expanded) "less" else "more") {
            val seen = remember { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(seen, enter = fadeIn(tween(180, if (expanded) 40 + 22 * (covers.size - STRIP).coerceAtMost(20) else 0)) + scaleIn(initialScale = .8f)) {
                Surface(onClick = { expanded = !expanded }, modifier = tile.testTag(if (expanded) "folder_covers_less" else "folder_covers_more"),
                    shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, border = BorderStroke(2.dp, surface)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (expanded) "Less" else "+$extra", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.semantics { contentDescription = if (expanded) "Show fewer books" else "Show all $count books" })
                    }
                }
            }
        } else if (extra > 0) Surface(tile, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, border = BorderStroke(2.dp, surface)) {
            Box(contentAlignment = Alignment.Center) {
                Text("+$extra", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/** The corner badges on a folder cover: the ReadAlong logo for a finished ReadAlong, a book for a linked EPUB. */
@Composable
private fun CoverBadges(badges: BookBadges?, modifier: Modifier = Modifier) {
    if (badges == null || (!badges.epub && !badges.readAlong)) return
    Row(modifier.semantics(mergeDescendants = true) {
        contentDescription = listOfNotNull("ReadAlong ready".takeIf { badges.readAlong }, "EPUB linked".takeIf { badges.epub }).joinToString(", ")
    }, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        if (badges.readAlong) ReadAlongBadge()
        if (badges.epub) EpubBadge()
    }
}

@Composable
private fun EpubBadge() {
    Box(Modifier.size(BADGE).clip(CircleShape).background(MaterialTheme.colorScheme.primary).border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_book), null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun ReadAlongBadge() {
    Box(Modifier.size(BADGE).clip(CircleShape).background(com.example.readalong.ui.theme.LogoPaleBlue).border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center) {
        // The logo's artwork sits inside padding of its own; drawn larger so the headphones and book fill the badge.
        androidx.compose.foundation.Image(painterResource(R.drawable.ic_readalong), null, Modifier.requiredSize(22.dp))
    }
}

/** What the two badges mean, under the covers. */
@Composable
private fun BadgeKey() {
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.clearAndSetSemantics { contentDescription = "Badges: book means EPUB linked, ReadAlong logo means ReadAlong ready" },
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            EpubBadge(); Text("= linked EPUB", style = style, color = color)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ReadAlongBadge(); Text("= ReadAlong ready", style = style, color = color)
        }
    }
}

private val BADGE = 18.dp

private fun summary(count: Int, lastScanAt: Long?): String {
    val books = if (count == 1) "1 book" else "$count books"
    val checked = lastScanAt?.let {
        if (System.currentTimeMillis() - it < DateUtils.MINUTE_IN_MILLIS) "checked just now"
        else "checked " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    }
    return listOfNotNull(books, checked).joinToString(" · ")
}

private fun grantedFolders(context: android.content.Context): Set<String> =
    context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
