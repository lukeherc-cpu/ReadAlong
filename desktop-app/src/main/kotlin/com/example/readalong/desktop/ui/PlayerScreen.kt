@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.readalong.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.readalong.desktop.core.Bookmark
import com.example.readalong.desktop.core.LibraryBook
import com.example.readalong.ui.components.MarqueeTitle
import com.example.readalong.ui.components.PlayPauseIcon

/** The book's colours over the whole window, darkening downwards, as on the phone's player. */
fun playerBackground(palette: CoverPalette, top: Color = lerp(palette.preview, Color(0xFF101418), .15f)) = Brush.verticalGradient(
    0f to top, .45f to lerp(palette.background, Color(0xFF101418), .35f), 1f to Color(0xFF0E1114))

@Composable
fun PlayerScreen(app: AppState, bookId: String) {
    val book by app.library.observe(bookId).collectAsState(app.library.book(bookId))
    val b = book ?: run { LaunchedEffect(Unit) { app.back() }; return }
    val palette = rememberCoverPalette(b)
    val animatedTop by animateColorAsState(palette.preview, tween(600))
    val animatedBottom by animateColorAsState(palette.background, tween(600))
    val reader = remember(bookId) { app.reader(bookId) }
    val text by reader.text.collectAsState()
    val playback by app.player.state.collectAsState()
    val clock = rememberPlaybackClock(app.player, bookId, b.positionMs)
    val current = playback.bookId == bookId
    val settings by app.settingsStore.settings.collectAsState()

    MaterialTheme(colorScheme = darkScheme(palette)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(playerBackground(CoverPalette(animatedBottom, animatedTop, palette.accent)))) {
            val wide = maxWidth >= 980.dp
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIconButton("ic_back", "Back (Esc)", { app.back() })
                        Spacer(Modifier.weight(1f))
                        if (text?.isEmpty == false) {
                            AppearanceMenu(app, themes = false)
                            AppIconButton("ic_fullscreen", "Full-screen reader", { app.open(Screen.Reader(bookId)) })
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            AppIconButton("ic_more", "More", { menu = true })
                            DropdownMenu(menu, { menu = false }) {
                                bookMenu(app, b).forEach { item -> DropdownMenuItem({ Text(item.label) }, { menu = false; item.onClick() }) }
                                if (b.epubPath != null) DropdownMenuItem({ Text(if (b.textSource == "EPUB") "Read the transcript instead of the EPUB" else "Read the EPUB") }, {
                                    menu = false; app.library.update(bookId) { it.copy(textSource = if (it.textSource == "EPUB") "GENERATED" else "EPUB") }
                                })
                                if (app.transcripts.info(bookId) != null) DropdownMenuItem({ Text("Remove ReadAlong") }, { menu = false; app.transcription.discard(bookId) })
                            }
                        }
                    }
                    Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (wide) 40.dp else 24.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        Column(Modifier.then(if (wide) Modifier.width(440.dp) else Modifier.fillMaxWidth()).fillMaxHeight().let { m -> val scroll = rememberScrollState(); m.smoothWheelScroll(scroll).verticalScroll(scroll) },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            FlipCover(app, b, palette, text, clock, flippable = !wide, settings.readerFont)
                            Spacer(Modifier.height(22.dp))
                            PlayerControls(app, b, clock, current, playback.playing, playback.speed, settings.skipBackSeconds, settings.skipForwardSeconds)
                        }
                        if (wide) Surface(Modifier.weight(1f).fillMaxHeight().padding(bottom = 28.dp), shape = RoundedCornerShape(Corners.large),
                            color = Color.White.copy(alpha = .06f), border = BorderStroke(1.dp, Color.White.copy(alpha = .06f))) {
                            TextPanel(app, b, text, clock, settings.readerFont, (settings.readerSize - 2).coerceAtLeast(16f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextPanel(app: AppState, book: LibraryBook, text: com.example.readalong.desktop.reading.ReadingText?, clock: State<Long>, font: String, size: Float) {
    val job by app.transcription.job.collectAsState()
    Box(Modifier.fillMaxSize()) {
        when {
            text == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
            text.isEmpty -> ReadAlongCard(app, book, Modifier.align(Alignment.Center).widthIn(max = 560.dp))
            else -> {
                val follow = rememberFollowState(text.paragraphAt(clock.value))
                FollowText(text, clock, follow, { seekBook(app, book.id, it) }, Modifier.fillMaxSize(), Fonts.reading(font), size,
                    contentPadding = PaddingValues(horizontal = 36.dp, vertical = 120.dp))
                // Fade the text out at the top and bottom edges.
                Box(Modifier.fillMaxWidth().height(60.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color(0x33000000), Color.Transparent))))
                ReturnToPosition(follow, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
                job?.takeIf { it.bookId == book.id }?.let { j ->
                    Surface(Modifier.align(Alignment.TopEnd).padding(14.dp), shape = CircleShape, color = Color.Black.copy(alpha = .35f)) {
                        Text("Making ReadAlong · ${(j.fraction * 100).toInt()}%", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .8f))
                    }
                }
            }
        }
    }
}

/** Seeks [bookId], opening it in the player first if another book is playing. */
fun seekBook(app: AppState, bookId: String, positionMs: Long) {
    if (app.player.state.value.bookId == bookId) { app.player.seekTo(positionMs); if (!app.player.state.value.playing) app.player.play() }
    else app.player.open(bookId, play = true, positionMs = positionMs)
}

/** The cover; on narrow windows it turns over, like the phone's, to show the text being read. */
@Composable
private fun FlipCover(app: AppState, book: LibraryBook, palette: CoverPalette, text: com.example.readalong.desktop.reading.ReadingText?, clock: State<Long>,
    flippable: Boolean, font: String) {
    var flipped by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (flipped && flippable) 180f else 0f, spring(dampingRatio = .8f, stiffness = 220f))
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered && flippable) 1f else 0f, spring(stiffness = 400f))
    Box(Modifier.widthIn(max = 440.dp).fillMaxWidth().aspectRatio(1f)
        .graphicsLayer { rotationY = rotation; cameraDistance = 14f * density; scaleX = 1f + lift * .015f; scaleY = 1f + lift * .015f }
        .shadow(24.dp, RoundedCornerShape(Corners.large), ambientColor = Color.Black, spotColor = Color.Black)
        .hoverable(interaction).then(if (flippable) Modifier.clickable(interaction, null) { flipped = !flipped }.pointerHoverIcon(PointerIcon.Hand) else Modifier)) {
        if (rotation <= 90f) BookCover(book, Modifier.fillMaxSize(), RoundedCornerShape(Corners.large), size = 900)
        else Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.clip(RoundedCornerShape(Corners.large)).background(lerp(palette.preview, Color.Black, .1f))) {
            if (text != null && !text.isEmpty) {
                val follow = rememberFollowState(text.paragraphAt(clock.value))
                FollowText(text, clock, follow, { seekBook(app, book.id, it) }, Modifier.fillMaxSize(), Fonts.reading(font), 19f,
                    contentPadding = PaddingValues(horizontal = 26.dp, vertical = 90.dp), anchor = .25f)
                AppIconButton("ic_fullscreen", "Full-screen reader", { app.open(Screen.Reader(book.id)) }, Modifier.align(Alignment.TopEnd).padding(10.dp))
            } else ReadAlongCard(app, book, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun PlayerControls(app: AppState, book: LibraryBook, clock: State<Long>, current: Boolean, playing: Boolean, speed: Float, back: Int, forward: Int) {
    val position = clock.value
    val chapterIndex = book.chapterAt(position)
    val chapter = book.chapters.getOrNull(chapterIndex)
    val chapterStart = chapter?.startMs ?: 0L
    val chapterEnd = book.chapters.getOrNull(chapterIndex + 1)?.startMs ?: book.durationMs
    Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
        MarqueeTitle(book.title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(chapter?.title ?: book.author ?: " ", style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = .72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        SeekBar(((position - chapterStart).toFloat() / (chapterEnd - chapterStart).coerceAtLeast(1)), { f ->
            seekBook(app, book.id, chapterStart + (f * (chapterEnd - chapterStart)).toLong())
        }, color = Color.White)
        Row(Modifier.fillMaxWidth()) {
            Text(timeText(position - chapterStart), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .75f))
            Spacer(Modifier.weight(1f))
            Text("${hoursMinutes(((book.durationMs - position) / speed).toLong())} left in book", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .75f))
            Spacer(Modifier.weight(1f))
            Text(timeText(chapterEnd - chapterStart), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .75f))
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            SkipButton("ic_rewind", back, "Back $back seconds (←)") { if (current) app.player.skip(-back * 1000L) else seekBook(app, book.id, position - back * 1000L) }
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val scale by animateFloatAsState(if (hovered) 1.06f else 1f, spring(dampingRatio = .55f, stiffness = 500f))
            Surface(modifier = Modifier.size(84.dp).graphicsLayer { scaleX = scale; scaleY = scale }.hoverable(interaction).pointerHoverIcon(PointerIcon.Hand),
                shape = CircleShape, color = MaterialTheme.colorScheme.primary, shadowElevation = 10.dp,
                onClick = { if (current) app.player.toggle() else app.player.open(book.id) }) {
                Box(contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimary) {
                        PlayPauseIcon(current && playing, Modifier.size(34.dp))
                    }
                }
            }
            SkipButton("ic_forward", forward, "Forward $forward seconds (→)") { if (current) app.player.skip(forward * 1000L) else seekBook(app, book.id, position + forward * 1000L) }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            var speedMenu by remember { mutableStateOf(false) }
            Box {
                LabeledAction("ic_speed", speedLabel(speed), { speedMenu = true })
                DropdownMenu(speedMenu, { speedMenu = false }) {
                    listOf(.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { s ->
                        DropdownMenuItem({ Text(speedLabel(s) + if (s == speed) "  ✓" else "") }, { speedMenu = false; if (!current) app.player.open(book.id, play = false); app.player.setSpeed(s) })
                    }
                }
            }
            val state by app.player.state.collectAsState()
            var sleepMenu by remember { mutableStateOf(false) }
            Box {
                LabeledAction(if (state.sleepEndsAt != null || state.sleepAtChapterEnd) "ic_sleep_filled" else "ic_sleep",
                    state.sleepEndsAt?.let { "${((it - System.currentTimeMillis()) / 60_000 + 1)} min" } ?: if (state.sleepAtChapterEnd) "Chapter end" else "Sleep",
                    { sleepMenu = true }, active = state.sleepEndsAt != null || state.sleepAtChapterEnd)
                DropdownMenu(sleepMenu, { sleepMenu = false }) {
                    DropdownMenuItem({ Text("Off") }, { sleepMenu = false; app.player.sleepAfter(null) })
                    listOf(5, 10, 15, 30, 45, 60, 90).forEach { m -> DropdownMenuItem({ Text("$m minutes") }, { sleepMenu = false; app.player.sleepAfter(m) }) }
                    DropdownMenuItem({ Text("End of chapter") }, { sleepMenu = false; app.player.sleepAtChapterEnd() })
                }
            }
            LabeledAction("ic_bookmark", "Bookmark", {
                val name = (book.chapters.getOrNull(chapterIndex)?.title?.let { "$it · " } ?: "") + timeText(position)
                app.library.update(book.id) { it.copy(bookmarks = (it.bookmarks + Bookmark(position, name, System.currentTimeMillis())).sortedBy { b -> b.positionMs }) }
                app.messages.tryEmit("Bookmarked $name")
            })
            var contents by remember { mutableStateOf(false) }
            LabeledAction("ic_chapters", "Chapters", { contents = true })
            if (contents) ContentsDialog(app, book, position) { contents = false }
        }
    }
}

@Composable
private fun SkipButton(icon: String, seconds: Int, description: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AppIconButton(icon, description, onClick, size = 52.dp, iconSize = 28.dp)
        Text("$seconds", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .8f))
    }
}

@Composable
private fun ContentsDialog(app: AppState, book: LibraryBook, position: Long, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    androidx.compose.ui.window.DialogWindow(onCloseRequest = onDismiss, title = book.title, state = androidx.compose.ui.window.rememberDialogState(size = androidx.compose.ui.unit.DpSize(520.dp, 640.dp))) {
        ReadAlongTheme(app.settingsStore.value.accent) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    TabRow(tab, containerColor = Color.Transparent) {
                        Tab(tab == 0, { tab = 0 }, text = { Text("Chapters") })
                        Tab(tab == 1, { tab = 1 }, text = { Text("Bookmarks (${book.bookmarks.size})") })
                    }
                    val current = book.chapterAt(position)
                    val list = rememberLazyListState(if (tab == 0) current.coerceAtLeast(0) else 0)
                    LazyColumn(Modifier.smoothWheelScroll(list), state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
                        if (tab == 0) {
                            if (book.chapters.isEmpty()) item { Text("This audiobook has no chapter marks.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            itemsIndexed(book.chapters) { i, chapter ->
                                ListItem({ Text(chapter.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    Modifier.clickable { seekBook(app, book.id, chapter.startMs); onDismiss() }.pointerHoverIcon(PointerIcon.Hand),
                                    trailingContent = { Text(timeText(chapter.startMs), style = MaterialTheme.typography.labelMedium) },
                                    colors = ListItemDefaults.colors(containerColor = if (i == current) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else Color.Transparent))
                            }
                        } else {
                            if (book.bookmarks.isEmpty()) item { Text("No bookmarks yet. Use Bookmark in the player to save a place.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            itemsIndexed(book.bookmarks) { _, mark ->
                                ListItem({ Text(mark.name) }, Modifier.clickable { seekBook(app, book.id, mark.positionMs); onDismiss() }.pointerHoverIcon(PointerIcon.Hand),
                                    trailingContent = { AppIconButton("ic_trash", "Remove bookmark", { app.library.update(book.id) { it.copy(bookmarks = it.bookmarks - mark) } }) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                            }
                        }
                    }
                }
            }
        }
    }
}

fun speedLabel(speed: Float): String = (if (speed * 10 % 1f == 0f) "%.1f×" else "%.2f×").format(java.util.Locale.ROOT, speed)
