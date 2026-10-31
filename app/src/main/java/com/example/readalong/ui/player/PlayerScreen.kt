package com.example.readalong.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.readalong.data.processing.progress
import com.example.readalong.R
import com.example.readalong.domain.model.Book
import com.example.readalong.playback.*
import com.example.readalong.ui.components.BookCover
import com.example.readalong.ui.components.navSharedCover

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(book: Book, state: PlaybackState, playback: PlaybackControls, onCollapse: () -> Unit, onFinished: (Boolean) -> Unit, reading: com.example.readalong.ui.reading.ReadingViewModel, onSpeechModel: () -> Unit, onFollow: () -> Unit, onFolders: () -> Unit = {}, onQueue: () -> Unit = {}) {
    LaunchedEffect(book.id) { reading.warmPreview() }
    val appSettings = com.example.readalong.ui.theme.LocalAppSettings.current
    var showText by rememberSaveable(book.id) { mutableStateOf(appSettings.openingView != "COVER") }
    val flip by animateFloatAsState(if (showText) 180f else 0f, tween(380), label = "Cover flip")
    var panel by remember { mutableStateOf<String?>(null) }
    val export = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/epub+zip")) { it?.let(reading::export) }
    var menu by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var draggedPosition by remember { mutableFloatStateOf(0f) }
    val bookmarks by reading.bookmarks.collectAsStateWithLifecycle()
    val timeRemaining by reading.timeRemaining.collectAsStateWithLifecycle()
    val job by reading.processingJob.collectAsStateWithLifecycle()
    val timingRepair by reading.needsAudioTimingRepair.collectAsStateWithLifecycle()
    var timingRepairDismissed by rememberSaveable(book.id) { mutableStateOf(false) }
    if (timingRepair && !timingRepairDismissed) AlertDialog(onDismissRequest = { timingRepairDismissed = true },
        title = { Text("Text timing is a little off") },
        text = { Text("The highlighting in this book's ReadAlong can drift from the audio. Fixing it makes the ReadAlong again; your listening position and EPUB are kept.") },
        confirmButton = { TextButton(onClick = { timingRepairDismissed = true; reading.rebuildTranscription() }) { Text("Fix timing") } },
        dismissButton = { TextButton(onClick = { timingRepairDismissed = true }) { Text("Later") } })
    val execution by reading.execution.collectAsStateWithLifecycle()
    val tipScope = rememberCoroutineScope()
    val tipApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.example.readalong.ReadAlongApplication
    fun hideCoverTip() { if (!appSettings.coverTipHidden) tipScope.launch { tipApp?.appPreferences?.update { it.copy(coverTipHidden = true) } } }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val readingError by reading.error.collectAsStateWithLifecycle()
    val start = state.chapterStartMs
    val chapterDuration = state.chapterDurationMs
    val chapterPosition = state.chapterPositionMs
    val palette = com.example.readalong.ui.components.rememberCoverPalette(book)
    val readerTheme = reading.preferences.collectAsStateWithLifecycle().value.theme
    val colors = com.example.readalong.ui.theme.playerScheme(palette).copy(secondaryContainer = palette.preview)
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    MaterialTheme(colorScheme = colors) {
    Box(Modifier.fillMaxSize().background(palette.background)) {
    val showBuffering by produceState(false, state.isLoading) {
        value = false
        if (state.isLoading) { kotlinx.coroutines.delay(600); value = true }
    }
    if (appSettings.blurredPlayer && !appSettings.alwaysBasicColor) com.example.readalong.ui.components.BlurredCover(book.coverUri, Modifier.fillMaxSize())
    // A book with no text yet flips its cover over once, the first time it's opened, to offer a ReadAlong.
    val passages by reading.passages.collectAsStateWithLifecycle()
    // (A sample book, or one whose text came another way, already has text without a ReadAlong job.)
    val notStarted = !book.isDemo && (job == null || job?.state == "WAITING_SELECTION") && passages.isEmpty()
    val introShown by reading.textIntroShown.collectAsStateWithLifecycle(null)
    LaunchedEffect(book.id, introShown) {
        if (introShown == false && !book.isDemo) {
            kotlinx.coroutines.delay(900)
            if (reading.processingJob.value.let { it == null || it.state == "WAITING_SELECTION" } && reading.passages.value.isEmpty()) showText = true
            reading.markTextIntroShown()
        }
    }
    val pickEpub = rememberEpubPicker(reading)
    val epub by reading.importedEdition.collectAsStateWithLifecycle()
    Scaffold(containerColor = Color.Transparent, contentColor = colors.onSurface) { insets ->
        Column(
            Modifier.fillMaxSize()
                .padding(insets),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A grab handle, like the pop-up sheets: tap it or pull down to close the player. The touch
            // area is the whole top bar's height and most of its width, and any pull that is mostly
            // downward counts (a diagonal thumb swipe too), as does a short quick flick.
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val threshold = with(density) { 40.dp.toPx() }
                val flick = with(density) { 16.dp.toPx() }
                val flickSpeed = with(density) { 700.dp.toPx() }
                Box(Modifier.align(Alignment.Center).size(width = 180.dp, height = 56.dp)
                    .pointerInput(Unit) {
                        val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                        var x = 0f; var y = 0f; var closed = false
                        fun downward() = y > 0f && y >= kotlin.math.abs(x) * .6f
                        detectDragGestures(onDragStart = { x = 0f; y = 0f; closed = false; tracker.resetTracking() },
                            onDragEnd = { if (!closed && downward() && (y > threshold || (y > flick && tracker.calculateVelocity().y > flickSpeed))) { closed = true; onCollapse() } }) { change, amount ->
                            change.consume(); tracker.addPosition(change.uptimeMillis, change.position)
                            x += amount.x; y += amount.y
                            if (!closed && downward() && y > threshold) { closed = true; onCollapse() }
                        }
                    }
                    .clickable(onClickLabel = "Close player", onClick = onCollapse)
                    .semantics { contentDescription = "Close player" }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).background(colors.onSurface.copy(alpha = .45f), RoundedCornerShape(2.dp)))
                }
                Box(Modifier.align(Alignment.CenterEnd)) {
                    IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more), "More options") }
                    DropdownMenu(menu, { menu = false }) {
                        // Adding the book's EPUB later swaps the ReadAlong to its exact text, so it leads the menu.
                        if (!book.isDemo) DropdownMenuItem(
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(if (epub != null) "Replace matching ebook" else "Add matching ebook", style = MaterialTheme.typography.titleSmall)
                                    Text("Exact text for this ReadAlong", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_book), null, tint = colors.primary) },
                            onClick = { menu = false; pickEpub() },
                            modifier = Modifier.padding(horizontal = 6.dp).background(colors.primary.copy(alpha = .12f), RoundedCornerShape(14.dp)).testTag("menu_add_epub"))
                        if (!book.isDemo) HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                        DropdownMenuItem(text = { Text(if (book.isFinished) "Mark as not finished" else "Mark as finished") }, onClick = { onFinished(!book.isFinished); menu = false })
                        DropdownMenuItem(text = { Text("Save ReadAlong as EPUB") }, onClick = { menu = false; export.launch("ReadAlong-${book.id}.epub") })
                        DropdownMenuItem(text = { Text("Bookmarks (${bookmarks.size})") }, onClick = { panel = "bookmarks"; menu = false })
                    }
                }
            }
            BoxWithConstraints(Modifier.widthIn(max = 960.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
                val wide = maxWidth >= 640.dp
                val artwork: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (book.mediaState == "MISSING") Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium), color = colors.errorContainer) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Can't find this book's audio. Its folder may have moved or been renamed.", color = colors.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = onFolders, contentPadding = PaddingValues(0.dp)) { Text("Choose folder again", color = colors.onErrorContainer) }
                            }
                        }
                        Box(Modifier.widthIn(max = if (wide) 360.dp else (screenHeight * 0.40f).coerceIn(260.dp, 380.dp)).fillMaxWidth().aspectRatio(1f)) {
                        Box(Modifier.fillMaxSize().graphicsLayer {
                                rotationY = flip
                                cameraDistance = 16 * density
                            }) {
                            // Keep the preview composed, measured and following even while the art faces forward.
                            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f; alpha = if (flip > 90f) 1f else 0f }
                                .then(if (flip <= 90f) Modifier.clearAndSetSemantics { } else Modifier)) {
                                if (notStarted) NoTextYet(book, reading, onNotNow = { showText = false })
                                else com.example.readalong.ui.reading.FollowCard(state, reading, onFollow, com.example.readalong.ui.reading.minimizedReadAlongColor(readerTheme, palette.preview),
                                    onCover = { showText = false }, visible = flip > 90f, modifier = if (showText) com.example.readalong.ui.reading.LocalReaderSurface.current else Modifier,
                                    colorOf = com.example.readalong.ui.reading.LocalReaderSurfaceColor.current)
                            }
                            if (flip <= 90f) Box(Modifier.fillMaxSize().testTag("flip_cover")
                                .clickable(role = Role.Button, onClickLabel = "Show the text") { showText = true; hideCoverTip() }) {
                                BookCover(book, Modifier.fillMaxSize().navSharedCover(com.example.readalong.ui.components.LocalPlayerCoverKey.current), large = true)
                                // Until it's been used once: the cover is also the way to the text.
                                if (!appSettings.coverTipHidden && job?.state == "READY") Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp),
                                    shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = .62f), contentColor = Color.White) {
                                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(painterResource(R.drawable.ic_highlighted_book), null, Modifier.size(18.dp))
                                        Text("Tap the cover for the text", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                        ReadAlongStatusPill(book, job, timeRemaining, reading, execution, onSpeechModel, onQueue,
                            Modifier.align(Alignment.BottomCenter).padding(12.dp))
                        }
                    }
                }
                val controls: @Composable ColumnScope.() -> Unit = {
                com.example.readalong.ui.components.MarqueeTitle(book.title, Modifier.fillMaxWidth(), MaterialTheme.typography.headlineMedium)
                Text(state.chapterTitle, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                val sliderColors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary, inactiveTrackColor = colors.onSurface.copy(alpha = .18f))
                Slider(
                    value = if (dragging) draggedPosition else chapterPosition.toFloat().coerceAtMost(chapterDuration.toFloat()),
                    onValueChange = { dragging = true; draggedPosition = it },
                    onValueChangeFinished = { playback.seek(start + draggedPosition.toLong()); dragging = false },
                    valueRange = 0f..chapterDuration.toFloat(),
                    modifier = Modifier.semantics { contentDescription = "Seek within chapter" },
                    // A plain round thumb on one continuous rounded track (no split thumb, gap or end dot).
                    thumb = { Box(Modifier.size(18.dp).background(colors.primary, CircleShape)) },
                    track = { sliderState -> SliderDefaults.Track(sliderState = sliderState, modifier = Modifier.height(5.dp), colors = sliderColors,
                        drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clockTime(chapterPosition), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                    Text(if (state.durationMs > 0) "${hoursMinutes(state.durationMs - state.positionMs)} left in book" else "Length unknown", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp), color = colors.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                    Text(clockTime(chapterDuration), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                }
                state.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }

                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    PlayerAction("Rewind ${appSettings.playerBack} seconds", R.drawable.ic_rewind, "${appSettings.playerBack}", { playback.skip(-appSettings.playerBack * 1000L) })
                    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
                    FilledIconButton(onClick = { haptics.performHapticFeedback(if (state.isPlaying) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn); playback.toggle() }, modifier = Modifier.size(76.dp), shape = CircleShape) {
                        com.example.readalong.ui.components.PlayPauseIcon(state.isPlaying, Modifier.size(36.dp))
                    }
                    PlayerAction("Forward ${appSettings.playerForward} seconds", R.drawable.ic_forward, "${appSettings.playerForward}", { playback.skip(appSettings.playerForward * 1000L) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PlayerAction("Playback speed", R.drawable.ic_speed, "${state.speed}×", { panel = "speed" })
                    // Running timer: the moon fills in and the label counts down.
                    val sleepLeft by produceState<Long?>(null, state.sleepEndsAt) {
                        while (true) { value = state.sleepEndsAt?.let { it - System.currentTimeMillis() }; kotlinx.coroutines.delay(15_000) }
                    }
                    val sleeping = state.sleepEndsAt != null || state.sleepAtChapterEnd
                    PlayerAction("Sleep timer", if (sleeping) R.drawable.ic_sleep_filled else R.drawable.ic_sleep,
                        when { state.sleepAtChapterEnd -> "End of chapter"; sleepLeft != null -> "${((sleepLeft!! + 59_999) / 60_000).coerceAtLeast(1)} min left"; else -> "Sleep" },
                        { panel = "sleep" })
                    PlayerAction("Bookmarks", R.drawable.ic_bookmark, "Bookmark", { panel = "bookmarks" })
                    PlayerAction("Chapters", R.drawable.ic_chapters, "Chapters", { panel = "chapters" })
                }
                readingError?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(12.dp))
                }
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Box(Modifier.weight(0.9f)) { artwork() }
                    Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = controls)
                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    artwork()
                    controls()
                }
            }
        }
    }
    if (showBuffering) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).semantics { contentDescription = "Buffering audio" })
    }
    if (panel == "bookmarks") BookmarksDialog(reading, state.positionMs, playback::seek, { panel = null })
    if (panel != null && panel != "bookmarks") {
        AlertDialog(
            onDismissRequest = { panel = null },
            title = { Text(when (panel) { "speed" -> "Playback speed"; "sleep" -> "Sleep timer"; "chapters" -> "Chapters"; "bookmarks" -> "Bookmarks"; else -> "ReadAlong" }) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    when (panel) {
                        "speed" -> items(PlaybackSpeeds) { speed -> TextButton(onClick = { playback.speed(speed); panel = null }, modifier = Modifier.fillMaxWidth()) { Text("$speed×") } }
                        "sleep" -> {
                            item { TextButton(onClick = { playback.sleep(null); panel = null }) { Text("Off") } }
                            items(listOf(5, 10, 15, 30, 45, 60)) { minutes -> TextButton(onClick = { playback.sleep(minutes); panel = null }) { Text("$minutes minutes") } }
                            item { TextButton(onClick = { playback.sleepAtChapterEnd(); panel = null }) { Text("End of chapter") } }
                        }
                        "chapters" -> {
                            if (book.isDemo) items(((state.durationMs + 1_799_999) / 1_800_000).toInt()) { index ->
                                TextButton(onClick = { playback.chapter(index); panel = null }) { Text("Chapter ${index + 1} · ${clockTime(index * 1_800_000L)}") }
                            } else items(state.chapters, key = { it.index }) { item ->
                                TextButton(onClick = { playback.chapter(item.index); panel = null }) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(item.title)
                                        Text(clockTime(item.startMs), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        "bookmarks" -> {
                            if (bookmarks.isEmpty()) item { Text("No bookmarks yet.") }
                            items(bookmarks, key = { it.id }) { bookmark ->
                                Row {
                                    TextButton(onClick = { playback.seek(bookmark.audioMs); panel = null }, modifier = Modifier.weight(1f)) { Text(clockTime(bookmark.audioMs)) }
                                    TextButton(onClick = { reading.deleteBookmark(bookmark.id) }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { panel = null }) { Text("Close") } },
        )
    }
    }
}

@Composable
private fun PlayerAction(description: String, icon: Int, label: String, onClick: () -> Unit) {
    Column(
        Modifier.widthIn(min = 48.dp).heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = description, onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), description, Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** "13h 47m", "47m" or "<1m": time left in the book without seconds. */
internal fun hoursMinutes(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) / 60_000)
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}
