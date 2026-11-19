package com.example.readalong.ui.player

import com.example.readalong.domain.i18n.tr
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
        title = { Text(tr("Text timing is a little off")) },
        text = { Text(tr("The highlighting in this book's ReadAlong can drift from the audio. Fixing it makes the ReadAlong again; your listening position and EPUB are kept.")) },
        confirmButton = { TextButton(onClick = { timingRepairDismissed = true; reading.rebuildTranscription() }) { Text(tr("Fix timing")) } },
        dismissButton = { TextButton(onClick = { timingRepairDismissed = true }) { Text(tr("Later")) } })
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
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
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
        // Also right after the book was added on its own, in place of the "Make a ReadAlong" pop-up.
        val justAdded = introShown != null && NewBookOffer.take(book.id)
        if ((introShown == false || justAdded) && !book.isDemo) {
            kotlinx.coroutines.delay(900)
            if (reading.processingJob.value.let { it == null || it.state == "WAITING_SELECTION" } && reading.passages.value.isEmpty()) showText = true
            reading.markTextIntroShown()
        }
    }
    val pickEpub = rememberEpubPicker(reading)
    val epub by reading.importedEdition.collectAsStateWithLifecycle()
    Scaffold(containerColor = Color.Transparent, contentColor = colors.onSurface) { insets ->
        // Upright, the top bar takes its own strip above the player. Sideways it floats over the top
        // instead, so the cover can use the full height of the screen.
        val topBar: @Composable () -> Unit = {
            // A grab handle, like the pop-up sheets: tap it or pull down to close the player. The touch
            // area is the whole top bar's height and most of its width, and any pull that is mostly
            // downward counts (a diagonal thumb swipe too), as does a short quick flick.
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                // No pressed highlight on the bar: the handle itself turns white while it's held or pulled.
                val handlePress = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val handlePressed by handlePress.collectIsPressedAsState()
                var handleDragging by remember { mutableStateOf(false) }
                val handleColor by androidx.compose.animation.animateColorAsState(
                    if (handlePressed || handleDragging) Color.White else colors.onSurface.copy(alpha = .45f), tween(150), label = "Handle color")
                val density = androidx.compose.ui.platform.LocalDensity.current
                val threshold = with(density) { 40.dp.toPx() }
                val flick = with(density) { 16.dp.toPx() }
                val flickSpeed = with(density) { 700.dp.toPx() }
                Box(Modifier.align(Alignment.Center).size(width = if (landscape) 120.dp else 180.dp, height = if (landscape) 44.dp else 56.dp)
                    .pointerInput(Unit) {
                        val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                        var x = 0f; var y = 0f; var closed = false
                        fun downward() = y > 0f && y >= kotlin.math.abs(x) * .6f
                        detectDragGestures(onDragStart = { x = 0f; y = 0f; closed = false; tracker.resetTracking(); handleDragging = true },
                            onDragCancel = { handleDragging = false },
                            onDragEnd = { handleDragging = false; if (!closed && downward() && (y > threshold || (y > flick && tracker.calculateVelocity().y > flickSpeed))) { closed = true; onCollapse() } }) { change, amount ->
                            change.consume(); tracker.addPosition(change.uptimeMillis, change.position)
                            x += amount.x; y += amount.y
                            if (!closed && downward() && y > threshold) { closed = true; onCollapse() }
                        }
                    }
                    .clickable(interactionSource = handlePress, indication = null, onClickLabel = "Close player", onClick = onCollapse)
                    .semantics { contentDescription = tr("Close player") }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).background(handleColor, RoundedCornerShape(2.dp)))
                }
                Box(Modifier.align(Alignment.CenterEnd)) {
                    IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more), tr("More options")) }
                    DropdownMenu(menu, { menu = false }) {
                        // Adding the book's EPUB later swaps the ReadAlong to its exact text, so it leads the menu.
                        // Every item has an icon in the same quiet colour; the EPUB item leads, with one line on why.
                        val menuIcon: @Composable (Int) -> Unit = { Icon(painterResource(it), null, Modifier.size(22.dp), tint = colors.onSurfaceVariant) }
                        if (!book.isDemo) DropdownMenuItem(
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(tr(if (epub != null) "Replace matching EPUB" else "Add matching EPUB"))
                                    Text(tr("Exact text for this ReadAlong"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            },
                            leadingIcon = { menuIcon(R.drawable.ic_book) },
                            onClick = { menu = false; pickEpub() },
                            modifier = Modifier.testTag("menu_add_epub"))
                        if (!book.isDemo) HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        if (!book.isDemo && tipApp != null) DropdownMenuItem(text = { Text(tr("Sound")) }, leadingIcon = { menuIcon(R.drawable.ic_headphones) },
                            onClick = { panel = "sound"; menu = false }, modifier = Modifier.testTag("menu_sound"))
                        DropdownMenuItem(text = { Text(tr(if (book.isFinished) "Mark as not finished" else "Mark as finished")) },
                            leadingIcon = { menuIcon(if (book.isFinished) R.drawable.ic_undo else R.drawable.ic_check_circle) },
                            onClick = { onFinished(!book.isFinished); menu = false })
                        DropdownMenuItem(text = { Text(tr("Save ReadAlong as EPUB")) }, leadingIcon = { menuIcon(R.drawable.ic_download) },
                            onClick = { menu = false; export.launch("ReadAlong-${book.id}.epub") })
                        DropdownMenuItem(text = { Text(tr("Bookmarks")) }, leadingIcon = { menuIcon(R.drawable.ic_bookmark) },
                            trailingIcon = { if (bookmarks.isNotEmpty()) Text(tr("${bookmarks.size}"), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) },
                            onClick = { panel = "bookmarks"; menu = false })
                        if (!book.isDemo) {
                            DropdownMenuItem(text = { Text(tr("Listening history")) }, leadingIcon = { menuIcon(R.drawable.ic_undo) },
                                onClick = { panel = "history"; menu = false }, modifier = Modifier.testTag("menu_history"))
                            DropdownMenuItem(text = { Text(tr("Character List")) }, leadingIcon = { menuIcon(R.drawable.ic_book) },
                                onClick = { panel = "characters"; menu = false }, modifier = Modifier.testTag("menu_characters"))
                        }
                    }
                }
            }
        }
        // Sideways, use the same inset on both sides (top/bottom and left/right) so the player is centred
        // on the physical screen, not nudged by a status bar or camera cutout on one edge only.
        val dir = androidx.compose.ui.platform.LocalLayoutDirection.current
        val sideIn = maxOf(insets.calculateStartPadding(dir), insets.calculateEndPadding(dir))
        val edgeIn = maxOf(insets.calculateTopPadding(), insets.calculateBottomPadding())
        Box(Modifier.fillMaxSize().padding(if (landscape) PaddingValues(horizontal = sideIn, vertical = edgeIn) else insets)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!landscape) topBar()
            BoxWithConstraints(Modifier.weight(1f).widthIn(max = 960.dp).fillMaxWidth().padding(horizontal = if (landscape) 0.dp else 24.dp)) {
                val wide = maxWidth >= 640.dp
                // Sideways on a phone the height is tight, so the controls get smaller type and buttons.
                val compact = wide && maxHeight < 440.dp
                // Wide, the screen splits into two equal halves: the cover sits in the middle of the left
                // half and the controls in the middle of the right half, both centred top to bottom. The
                // cover fills the height it has, less 20dp above and below (and at most ~45% of the width).
                val wideCover = minOf(360.dp, maxHeight - if (landscape) 40.dp else 8.dp, maxWidth * 0.45f).coerceAtLeast(160.dp)
                val wideControls = maxOf(wideCover, 320.dp).coerceAtMost(maxWidth / 2 - 24.dp)
                val artwork: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (book.mediaState == "MISSING") Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium), color = colors.errorContainer) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(tr("Can't find this book's audio. Its folder may have moved or been renamed."), color = colors.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = onFolders, contentPadding = PaddingValues(0.dp)) { Text(tr("Choose folder again"), color = colors.onErrorContainer) }
                            }
                        }
                        Box(Modifier.widthIn(max = if (wide) wideCover else (screenHeight * 0.40f).coerceIn(260.dp, 380.dp)).fillMaxWidth().aspectRatio(1f)) {
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
                                        Text(tr("Tap the cover for the text"), style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                        ReadAlongStatusPill(book, job, timeRemaining, reading, execution, onSpeechModel, onQueue,
                            Modifier.align(Alignment.BottomStart).padding(12.dp))
                        }
                    }
                }
                val controls: @Composable ColumnScope.() -> Unit = {
                com.example.readalong.ui.components.MarqueeTitle(book.title, Modifier.fillMaxWidth(), if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium)
                Text(tr(state.chapterTitle), style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant,
                    maxLines = if (wide) 1 else Int.MAX_VALUE, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                val sliderColors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary, inactiveTrackColor = colors.onSurface.copy(alpha = .18f))
                Slider(
                    value = if (dragging) draggedPosition else chapterPosition.toFloat().coerceAtMost(chapterDuration.toFloat()),
                    onValueChange = { dragging = true; draggedPosition = it },
                    onValueChangeFinished = { playback.seek(start + draggedPosition.toLong()); dragging = false },
                    valueRange = 0f..chapterDuration.toFloat(),
                    modifier = Modifier.then(if (compact) Modifier.height(32.dp) else Modifier).semantics { contentDescription = tr("Seek within chapter") },
                    // A plain round thumb on one continuous rounded track (no split thumb, gap or end dot).
                    thumb = { Box(Modifier.size(18.dp).background(colors.primary, CircleShape)) },
                    track = { sliderState -> SliderDefaults.Track(sliderState = sliderState, modifier = Modifier.height(5.dp), colors = sliderColors,
                        drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tr(clockTime(chapterPosition)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                    Text(tr(if (state.durationMs > 0) "${hoursMinutes(state.durationMs - state.positionMs)} left in book" else "Length unknown"), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp), color = colors.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                    Text(tr(clockTime(chapterDuration)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                }
                state.error?.let { Text(tr(it), color = colors.error, style = MaterialTheme.typography.bodySmall) }

                Row(Modifier.fillMaxWidth().padding(vertical = if (wide) 2.dp else 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    PlayerAction("Rewind ${appSettings.playerBack} seconds", R.drawable.ic_rewind, "${appSettings.playerBack}", { playback.skip(-appSettings.playerBack * 1000L) })
                    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
                    FilledIconButton(onClick = { haptics.performHapticFeedback(if (state.isPlaying) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn); playback.toggle() }, modifier = Modifier.size(if (compact) 64.dp else 76.dp), shape = CircleShape) {
                        com.example.readalong.ui.components.PlayPauseIcon(state.isPlaying, Modifier.size(if (compact) 30.dp else 36.dp))
                    }
                    PlayerAction("Forward ${appSettings.playerForward} seconds", R.drawable.ic_forward, "${appSettings.playerForward}", { playback.skip(appSettings.playerForward * 1000L) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PlayerAction("Playback speed", R.drawable.ic_speed, com.example.readalong.domain.model.speedLabel(state.speed), { panel = "speed" })
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
                readingError?.let { Text(tr(it), color = colors.error, style = MaterialTheme.typography.bodySmall) }
                if (!wide) Spacer(Modifier.height(12.dp))
                }
                if (wide) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Box(Modifier.width(wideCover)) { artwork() } }
                    // Scrolls only if a very short window still can't fit it all.
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Column(Modifier.width(wideControls).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp), content = controls)
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    artwork()
                    controls()
                }
            }
        }
        if (landscape) topBar()
        }
    }
    if (showBuffering) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).semantics { contentDescription = tr("Buffering audio") })
    }
    if (panel == "bookmarks") BookmarksDialog(reading, state.positionMs, playback::seek, { panel = null }, state.chapters)
    if (panel == "sound" && tipApp != null) com.example.readalong.ui.sound.BookSoundSheet(tipApp, book.id, book.title) { panel = null }
    if (panel == "history") HistorySheet(book.id, state.positionMs, state.chapterTitle, playback::seek, { panel = null })
    if (panel == "characters") CharacterListSheet(book.id, state.positionMs, state.chapters, playback::seek, { panel = null }, book.title, book.author)
    if (panel != null && panel !in setOf("bookmarks", "history", "characters", "sound")) PlayerPanel(panel!!, book, state, playback, close = { panel = null })
    }
}

/** Speed, sleep timer and chapters: one sheet each, with the current choice ticked and in view. */
@Composable
private fun PlayerPanel(panel: String, book: Book, state: PlaybackState, playback: PlaybackControls, close: () -> Unit) {
    when (panel) {
        "speed" -> SpeedSheet(state.speed, book.title, playback::speed, close)
        "sleep" -> {
            val left = state.sleepEndsAt?.let { it - System.currentTimeMillis() }
            val status = when {
                state.sleepAtChapterEnd -> "Stops at end of chapter"
                left != null -> "Stops in ${((left + 59_999) / 60_000).coerceAtLeast(1)} min"
                else -> null
            }
            val chapterLeft = ((state.chapterDurationMs - state.chapterPositionMs) / state.speed.coerceAtLeast(.1f)).toLong()
            com.example.readalong.ui.components.ActionSheet(close, "Sleep timer", status) {
                item { com.example.readalong.ui.components.SheetRow("Off", { playback.sleep(null); close() }, selected = status == null) }
                items(listOf(5, 10, 15, 30, 45, 60)) { minutes ->
                    com.example.readalong.ui.components.SheetRow("$minutes minutes", { playback.sleep(minutes); close() }, selected = false)
                }
                item {
                    com.example.readalong.ui.components.SheetRow("End of chapter", { playback.sleepAtChapterEnd(); close() },
                        meta = if (state.sleepAtChapterEnd) null else "in ${hoursMinutes(chapterLeft)}", selected = state.sleepAtChapterEnd)
                }
                item {
                    val shake = com.example.readalong.ui.theme.LocalAppSettings.current.shakeToReset
                    Text(tr(if (shake) "Fades out over the last 20 seconds. Shake the phone to start the timer again." else "Fades out over the last 20 seconds. Shake to reset is off in Settings → Sleep timer."),
                        Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        "chapters" -> {
            val demoCount = ((state.durationMs + 1_799_999) / 1_800_000).toInt()
            val count = if (book.isDemo) demoCount else state.chapters.size
            val playing = if (book.isDemo) (state.positionMs / 1_800_000).toInt() else state.chapterIndex
            val at = if (book.isDemo) playing else state.chapters.indexOfFirst { it.index == playing }.coerceAtLeast(0)
            com.example.readalong.ui.components.ActionSheet(close, "Chapters", if (count == 1) "1 chapter" else "$count chapters",
                listState = androidx.compose.foundation.lazy.rememberLazyListState((at - 2).coerceAtLeast(0))) {
                if (book.isDemo) items(demoCount) { index ->
                    com.example.readalong.ui.components.SheetRow("Chapter ${index + 1}", { playback.chapter(index); close() },
                        meta = clockTime(index * 1_800_000L), selected = index == playing)
                } else items(state.chapters, key = { it.index }) { item ->
                    com.example.readalong.ui.components.SheetRow(item.title, { playback.chapter(item.index); close() },
                        meta = clockTime(item.startMs), selected = item.index == playing)
                }
            }
        }
    }
}

@Composable
private fun PlayerAction(description: String, icon: Int, label: String, onClick: () -> Unit) {
    Column(
        Modifier.widthIn(min = 48.dp).heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = description, onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), tr(description), Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(tr(label), style = MaterialTheme.typography.labelSmall, maxLines = 1)
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

/** 0.5× to 3× in 0.05 steps: a slider with − and +, quick picks, and whether this book keeps its own speed. */
@Composable
private fun SpeedSheet(speed: Float, title: String, change: (Float) -> Unit, close: () -> Unit) {
    val settings = com.example.readalong.ui.theme.LocalAppSettings.current
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.example.readalong.ReadAlongApplication
    val scope = rememberCoroutineScope()
    var value by remember { mutableFloatStateOf(speed) }
    fun set(next: Float) { value = com.example.readalong.domain.model.roundSpeed(next); change(value) }
    com.example.readalong.ui.components.ActionSheet(close, "Playback speed", if (settings.speedPerBook) "Saved for $title" else "Same for every book") {
        item {
            Text(tr(com.example.readalong.domain.model.speedLabel(value)), Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("speed_value"),
                style = MaterialTheme.typography.displaySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedIconButton(onClick = { set(value - .05f) }, Modifier.testTag("speed_down")) { Icon(painterResource(R.drawable.ic_remove), tr("Slower"), Modifier.size(22.dp)) }
                Slider(value, { value = com.example.readalong.domain.model.roundSpeed(it) }, Modifier.weight(1f).testTag("speed_slider"),
                    valueRange = com.example.readalong.domain.model.MIN_SPEED..com.example.readalong.domain.model.MAX_SPEED, onValueChangeFinished = { change(value) })
                OutlinedIconButton(onClick = { set(value + .05f) }, Modifier.testTag("speed_up")) { Icon(painterResource(R.drawable.ic_add), tr("Faster"), Modifier.size(22.dp)) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.example.readalong.domain.model.SpeedChips.forEach { chip ->
                    FilterChip(value == chip, { set(chip) }, { Text(tr(com.example.readalong.domain.model.speedLabel(chip))) }, Modifier.testTag("speed_chip_$chip"))
                }
            }
        }
        if (app != null) item {
            com.example.readalong.ui.sound.SoundSwitch("Remember for this book", "Other books keep their own speed", settings.speedPerBook,
                { on -> scope.launch { app.appPreferences.update { it.copy(speedPerBook = on) } } })
        }
    }
}
