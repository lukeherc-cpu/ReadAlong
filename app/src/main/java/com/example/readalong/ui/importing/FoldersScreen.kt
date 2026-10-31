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
import com.example.readalong.data.local.FolderEntity
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
    val context = LocalContext.current
    val app = context.applicationContext as? com.example.readalong.ReadAlongApplication
    val library by remember { app?.bookRepository?.observeBooks() ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val booksById = remember(library) { library.associateBy { it.id } }
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
    var granted by remember { mutableStateOf(grantedFolders(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = grantedFolders(context) }
    val shown = folders.filter { it.mode == FolderMode.LIBRARY.name }

    Scaffold { insets ->
        PullToRefreshBox(isRefreshing = scanningFolder != null, onRefresh = model::refreshLibrary, modifier = Modifier.fillMaxSize().padding(insets)) {
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
                        FolderCard(folder, covers, ids.size,
                            scanning = scanningFolder == folder.name || (busy && folder.error == SCAN_INCOMPLETE),
                            lostAccess = folder.uri !in granted, enabled = !busy,
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
private fun FolderCard(folder: FolderEntity, covers: List<Book>, count: Int, scanning: Boolean, lostAccess: Boolean, enabled: Boolean,
    onCheck: () -> Unit, onRemove: () -> Unit, onChooseAgain: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(folder.name, style = MaterialTheme.typography.titleLarge)
                    when {
                        lostAccess -> Text("ReadAlong lost access to this folder", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
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
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
            if (!lostAccess && covers.isNotEmpty()) CoverStrip(covers, count)
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
private fun CoverStrip(covers: List<Book>, count: Int) {
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
                    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(2.dp, surface)) {
                        BookCover(book, Modifier.size(52.dp), showTitle = false)
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
