package com.example.readalong.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import com.example.readalong.desktop.core.LibraryBook
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File

/** The phone's three source shades: the accent, then two lighter steps toward the background. */
@Composable
internal fun sourceShades(): List<Pair<Color, Color>> {
    val colors = MaterialTheme.colorScheme
    return listOf(0f, .3f, .55f).map { mix ->
        val shade = lerp(colors.primary, colors.background, mix)
        shade to if (mix < .5f) colors.onPrimary else colors.onBackground
    }
}

/** A rounded, title-only source button: the + popover's options. [hero] is the large first one. */
@Composable
private fun SourceOption(shade: Pair<Color, Color>, icon: String, title: String, hero: Boolean = false, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = if (hero) 92.dp else 60.dp).pointerHoverIcon(PointerIcon.Hand),
        shape = RoundedCornerShape(if (hero) 26.dp else 20.dp), colors = ButtonDefaults.buttonColors(containerColor = shade.first, contentColor = shade.second),
        contentPadding = PaddingValues(horizontal = 20.dp)) {
        Icon(AppIcons.get(icon), null, Modifier.size(if (hero) 32.dp else 22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, Modifier.weight(1f), style = if (hero) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall)
    }
}

/** The phone's + button, as a popover under it: one book from a folder, one audio file, Audible, and the folders page. */
@Composable
fun AddBookButton(app: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppIconButton("ic_add", "Add audiobook", { open = !open })
        if (open) Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, 52), onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            val shown = remember { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(shown, enter = fadeIn(tween(160)) + scaleIn(spring(dampingRatio = .8f, stiffness = 700f), initialScale = .92f,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f))) {
                Surface(Modifier.width(340.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 12.dp) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Add audiobook", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                        val shades = sourceShades()
                        SourceOption(shades[0], "ic_folder", "Add one book", hero = true) { open = false; chooseFolder("Choose an audiobook folder")?.let(app::chooseFolder) }
                        SourceOption(shades[1], "ic_play", "Add audio file") { open = false; chooseAudioFile()?.let { app.addPaths(listOf(it)) } }
                        SourceOption(shades[2], "ic_book_add", "Import from Audible") { open = false; app.open(Screen.Audible) }
                        TextButton({ open = false; app.open(Screen.Folders) }, Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                            Icon(AppIcons.get("ic_bookshelf"), null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Manage library folders")
                        }
                        Text("Or drop folders and files on the window", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** What a chosen folder holds, before anything is added: many books as a grid of covers, or one book large. */
@Composable
fun FoundDialog(app: AppState) {
    val found = app.found ?: return
    var oneBook by remember(found) { mutableStateOf(found.books.size == 1) }
    Dialog(onDismissRequest = { app.found = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shown = remember(found) { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(shown, enter = fadeIn(tween(200)) + slideInVertically(spring(dampingRatio = .85f, stiffness = 500f)) { it / 12 } + scaleIn(initialScale = .97f)) {
            Surface(Modifier.animateContentSize(tween(320, easing = FastOutSlowInEasing)), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 16.dp) {
                if (oneBook) OneBookFound(found, onAdd = { app.confirmFound(asOneBook = found.books.size > 1) }, onBack = if (found.books.size > 1) ({ oneBook = false }) else null, onCancel = { app.found = null })
                else ManyBooksFound(found, onAdd = { app.confirmFound(asOneBook = false) }, onOneBook = { oneBook = true })
            }
        }
    }
}

@Composable
private fun ManyBooksFound(found: FolderFound, onAdd: () -> Unit, onOneBook: () -> Unit) {
    val books = found.books
    val shownBooks = if (books.size <= 12) books else books.take(11)
    val rest = books.size - shownBooks.size
    Column(Modifier.width(640.dp).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${books.size} audiobooks found", style = MaterialTheme.typography.headlineSmall)
            Text("in your “${found.folder.name}” folder", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val tiles = shownBooks.map { it.name to it.images.firstOrNull()?.path } + if (rest > 0) listOf("more, added too" to null) else emptyList()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(6).forEachIndexed { row, items ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items.forEachIndexed { col, (name, image) ->
                        val index = row * 6 + col
                        val delayMs = 160 + index * 40
                        val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                        AnimatedVisibility(visible, Modifier.weight(1f), enter = fadeIn(tween(240, delayMs)) + scaleIn(tween(320, delayMs, FastOutSlowInEasing), initialScale = .9f)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (index >= shownBooks.size) Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                    Text("+$rest", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                } else FoundCover(name, image, index, Modifier.fillMaxWidth())
                                Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    repeat(6 - items.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onOneBook, Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("It's really one book") }
            Button(onAdd, Modifier.heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand), shape = CircleShape, contentPadding = PaddingValues(horizontal = 28.dp)) { Text("Add ${books.size} books") }
        }
        Text("ReadAlong will keep checking this folder for new books.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OneBookFound(found: FolderFound, onAdd: () -> Unit, onBack: (() -> Unit)?, onCancel: () -> Unit) {
    val single = found.books.singleOrNull()
    val name = single?.name ?: found.folder.name
    val epub = found.books.any { it.epubs.isNotEmpty() }
    val transcript = found.books.any { it.transcripts.isNotEmpty() }
    val title = "1 audiobook found" + when {
        epub && transcript -> " with matching EPUB and ReadAlong transcript"
        epub -> " with matching EPUB"
        transcript -> " with ReadAlong transcript"
        else -> ""
    }
    Row(Modifier.width(580.dp).padding(28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        FoundCover(name, found.books.firstNotNullOfOrNull { it.images.firstOrNull() }?.path, 0, Modifier.size(176.dp), large = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(name, style = MaterialTheme.typography.titleMedium)
            if (single == null) Text("All ${found.books.size} parts play as one audiobook, in order.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) TextButton(onBack, Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("They're ${found.books.size} books") }
                else TextButton(onCancel, Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("Cancel") }
                Button(onAdd, Modifier.weight(1f).heightIn(min = 48.dp).pointerHoverIcon(PointerIcon.Hand), shape = CircleShape) { Text("Add book") }
            }
        }
    }
}

/** The folder's cover image when it has one, else a square in one of the accent's shades with the first letter. */
@Composable
private fun FoundCover(name: String, image: String?, index: Int, modifier: Modifier, large: Boolean = false) {
    val bitmap = rememberCover(image, if (large) 400 else 200)
    val colors = MaterialTheme.colorScheme
    val shade = lerp(colors.primary, colors.background, listOf(0f, .3f, .15f, .5f, .22f)[index % 5])
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(if (large) 14.dp else 10.dp)).background(shade), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.trim().take(1).uppercase(), color = colors.onPrimary, style = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.titleLarge)
    }
}

/** A dashed rounded outline: where a folder can be dropped. */
private fun Modifier.dashedOutline(color: Color, radius: Dp) = drawBehind {
    drawRoundRect(color, cornerRadius = CornerRadius(radius.toPx()), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
}

/** Library folders: one card per folder with its covers and book count, actions behind ⋯, and a drop zone. */
@Composable
fun FoldersScreen(app: AppState) {
    val folders by app.library.folders.collectAsState()
    val books by app.library.books.collectAsState()
    val scan by app.importer.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 28.dp, vertical = 12.dp)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) { AppIconButton("ic_back", "Back to library", { app.back() }) }
        }
        Column(Modifier.widthIn(max = 820.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Library folders", style = MaterialTheme.typography.headlineMedium)
                Text("ReadAlong checks these for new books each time it opens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Folders two to a row, then the drop zone in the next free place (null).
            val cells: List<String?> = folders + listOf(null)
            cells.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { folder ->
                        val cell = Modifier.weight(1f).fillMaxHeight()
                        if (folder != null) {
                            val root = File(folder).absolutePath + File.separator
                            val inside = books.filter { it.parts.firstOrNull()?.path?.startsWith(root) == true }.sortedBy { it.title.lowercase() }
                            FolderCard(app, folder, inside, scanning = scan.running && !scan.adding, scanLabel = scan.label, modifier = cell)
                        } else Box(cell.heightIn(min = 96.dp).dashedOutline(MaterialTheme.colorScheme.outline, 24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(AppIcons.get("ic_down"), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Drop a folder here to add it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ chooseFolder("Choose a folder of audiobooks")?.let(app::chooseFolder) }, Modifier.width(420.dp).heightIn(min = 60.dp).pointerHoverIcon(PointerIcon.Hand), shape = CircleShape) {
                    Icon(AppIcons.get("ic_add"), null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Text("Add audiobooks", style = MaterialTheme.typography.titleMedium)
                }
                TextButton({ app.open(Screen.Audible) }, Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("Import from Audible") }
            }
        }
    }
}

@Composable
private fun FolderCard(app: AppState, folder: String, books: List<com.example.readalong.desktop.core.LibraryBook>, scanning: Boolean, scanLabel: String, modifier: Modifier) {
    val file = File(folder)
    val missing = !file.isDirectory
    var menu by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Column(Modifier.animateContentSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(file.name.ifEmpty { folder }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when {
                        missing -> Text("This folder can't be found. Is the drive plugged in?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        scanning -> Text(scanLabel.ifEmpty { "Looking for books…" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else -> Text(if (books.size == 1) "1 book" else "${books.size} books", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (missing) FilledTonalButton({ chooseFolder("Choose this folder again")?.let { app.removeFolder(folder); app.chooseFolder(it) } }, Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("Choose again") }
                else Box {
                    AppIconButton("ic_more", "Options for ${file.name}", { menu = true }, size = 40.dp, iconSize = 20.dp)
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Check for new books") }, { menu = false; app.scope.launch { runCatching { app.importer.rescan() } } })
                        DropdownMenuItem({ Text("Show in file manager") }, { menu = false; runCatching { Desktop.getDesktop().open(file) } })
                        DropdownMenuItem({ Text("Remove folder", color = MaterialTheme.colorScheme.error) }, { menu = false; removing = true })
                    }
                }
            }
            if (scanning && !missing) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
            if (!missing && books.isNotEmpty()) FolderCovers(books)
        }
    }
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text("Remove this folder?") },
        text = { Text("Its books leave your library, along with their ReadAlongs and listening progress. The audio files stay where they are.") },
        confirmButton = { TextButton({ removing = false; app.removeFolder(folder) }) { Text("Remove folder", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ removing = false }) { Text("Cancel") } })
}

/**
 * The empty library's set-up: how to set up the library (local files or Audible), then that source's page.
 * The title inks in word by word, then the line under it all at once.
 */
@Composable
fun LibrarySetup(app: AppState, scanning: Boolean) {
    var step by remember { mutableIntStateOf(0) }
    AnimatedContent(step, Modifier.fillMaxSize(), transitionSpec = {
        val forward = targetState > initialState
        (slideInHorizontally(tween(460, easing = CubicBezierEasing(.2f, .8f, .2f, 1f))) { if (forward) it else -it / 5 } + fadeIn(tween(320))) togetherWith
            (slideOutHorizontally(tween(460, easing = CubicBezierEasing(.2f, .8f, .2f, 1f))) { if (forward) -it / 5 else it } + fadeOut(tween(240)))
    }, label = "Library setup") { shown ->
        Box(Modifier.fillMaxSize()) {
            if (shown != 0) Box(Modifier.padding(horizontal = 28.dp, vertical = 12.dp)) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) { AppIconButton("ic_back", "Back", { step = 0 }) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                when (shown) {
                    0 -> SetupChoose(onLocal = { step = 1 }, onAudible = { step = 2 })
                    1 -> SetupLocal(app, scanning)
                    else -> SetupAudible(app)
                }
            }
        }
    }
}

@Composable
private fun SetupLogo(size: Dp) = Image(AppIcons.get("ic_readalong"), "ReadAlong", Modifier.size(size))

@Composable
private fun SetupChoose(onLocal: () -> Unit, onAudible: () -> Unit) {
    SetupLogo(112.dp)
    val words = listOf("Listen", "and", "read", "along")
    val ink = MaterialTheme.colorScheme.onBackground
    val grey = lerp(ink, MaterialTheme.colorScheme.background, .45f)
    val spoken = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(650); spoken.animateTo(words.size + 1.5f, tween(320 * (words.size + 2), easing = LinearEasing)) }
    fun shade(at: Float) = lerp(grey, ink, (spoken.value - at).coerceIn(0f, 1f))
    Text(buildAnnotatedString {
        words.forEachIndexed { i, word -> if (i > 0) append(" "); withStyle(SpanStyle(color = shade(i.toFloat()))) { append(word) } }
    }, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
    Text("Your audiobooks, with the words on screen as they're read to you.", style = MaterialTheme.typography.bodyLarge,
        color = shade(words.size + .5f), textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 12.dp))
    Text("How would you like to set up your library?", style = MaterialTheme.typography.titleMedium)
    val shades = sourceShades()
    Row(Modifier.width(620.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SetupCard(shades[0], "ic_folder", "Local files", "Audiobooks already on this computer", Modifier.weight(1f).fillMaxHeight(), onLocal)
        SetupCard(shades[1], "ic_book_add", "Import from Audible", "Books you own on Audible", Modifier.weight(1f).fillMaxHeight(), onAudible)
    }
}

@Composable
private fun SetupCard(shade: Pair<Color, Color>, icon: String, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick, modifier.heightIn(min = 168.dp).pointerHoverIcon(PointerIcon.Hand), shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = shade.first, contentColor = shade.second), contentPadding = PaddingValues(22.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(shade.second.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                Icon(AppIcons.get(icon), null, Modifier.size(28.dp))
            }
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = shade.second.copy(alpha = .85f))
            }
        }
    }
}

@Composable
private fun SetupLocal(app: AppState, scanning: Boolean) {
    SetupLogo(104.dp)
    Text(if (scanning) "Looking for audiobooks…" else "Add your audiobooks", style = MaterialTheme.typography.headlineMedium)
    Text("Pick a folder. It can hold one book or your whole library. ReadAlong works out which and shows you before adding.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 460.dp).padding(bottom = 12.dp))
    Column(Modifier.width(460.dp).dashedOutline(MaterialTheme.colorScheme.outline, 28.dp).padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button({ chooseFolder()?.let(app::chooseFolder) }, Modifier.fillMaxWidth().heightIn(min = 60.dp).pointerHoverIcon(PointerIcon.Hand), shape = CircleShape, enabled = !scanning) {
            Text("Choose folder", style = MaterialTheme.typography.titleMedium)
        }
        Text("or drop it here", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TextButton({ chooseAudioFile()?.let { app.addPaths(listOf(it)) } }, Modifier.pointerHoverIcon(PointerIcon.Hand),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground)) {
        Icon(AppIcons.get("ic_play"), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Or add a single audio file")
    }
}

@Composable
private fun SetupAudible(app: AppState) {
    SetupLogo(104.dp)
    Text("Import from Audible", style = MaterialTheme.typography.headlineMedium)
    Text("Sign in to Audible in your browser, then pick the books to download. They're saved on this computer and added to your library.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 460.dp).padding(bottom = 12.dp))
    Button({ app.open(Screen.Audible) }, Modifier.width(460.dp).heightIn(min = 60.dp).pointerHoverIcon(PointerIcon.Hand), shape = CircleShape) {
        Text("Sign in to Audible", style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Overlapping covers with "+N" at the end. Clicking "+N" spreads the covers apart and the rest pop in after them
 * while the card grows; "Less" folds them back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderCovers(books: List<LibraryBook>) {
    var expanded by remember { mutableStateOf(false) }
    val canExpand = books.size > 5
    LaunchedEffect(canExpand) { if (!canExpand) expanded = false }
    val spread by animateFloatAsState(if (expanded) 1f else 0f, spring(dampingRatio = .85f, stiffness = 380f))
    val shown = if (expanded) books else books.take(5)
    val shape = RoundedCornerShape(12.dp)
    FlowRow(Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = .9f, stiffness = 320f)),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEachIndexed { index, book ->
            key(book.id) {
                val seen = remember { MutableTransitionState(index < 5 && !expanded).apply { targetState = true } }
                val delay = if (index < 5) 0 else 40 + 22 * (index - 5).coerceAtMost(20)
                // Each cover reports itself 20dp narrower when folded, so the next one sits over it (12dp overlap after the 8dp gap).
                AnimatedVisibility(seen, Modifier.layout { m, c ->
                        val p = m.measure(c)
                        layout((p.width - 20.dp.toPx() * (1f - spread)).toInt(), p.height) { p.place(0, 0) }
                    }, enter = fadeIn(tween(200, delay)) + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 500f), initialScale = .6f)) {
                    BookCover(book, Modifier.size(56.dp).border(2.dp, MaterialTheme.colorScheme.surface, shape), shape = shape, size = 160)
                }
            }
        }
        if (canExpand) key(if (expanded) "less" else "more") {
            val seen = remember { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(seen, enter = fadeIn(tween(180, if (expanded) 40 + 22 * (books.size - 5).coerceAtMost(20) else 0)) + scaleIn(initialScale = .8f)) {
                Box(Modifier.size(56.dp).clip(shape).background(MaterialTheme.colorScheme.primaryContainer).border(2.dp, MaterialTheme.colorScheme.surface, shape)
                    .clickable(onClickLabel = if (expanded) "Show fewer books" else "Show all ${books.size} books") { expanded = !expanded }
                    .pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center) {
                    Text(if (expanded) "Less" else "+${books.size - 5}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}
