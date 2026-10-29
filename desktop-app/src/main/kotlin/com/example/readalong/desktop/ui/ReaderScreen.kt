@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.readalong.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.readalong.desktop.reading.ReadingText
import com.example.readalong.domain.reading.PassageSearch
import com.example.readalong.domain.reading.SearchPassage
import com.example.readalong.domain.reading.activeSentenceIndex
import com.example.readalong.ui.components.PlayPauseIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ReaderColors(val page: Color, val ink: Color, val chrome: Color)
fun readerColors(theme: String) = when (theme) {
    "SEPIA" -> ReaderColors(Color(0xFFF3EAD7), Color(0xFF3A2F25), Color(0xFFE8DCC3))
    "LIGHT" -> ReaderColors(Color(0xFFFAFAF8), Color(0xFF1D2125), Color(0xFFEDEEF0))
    else -> ReaderColors(Color(0xFF121518), Color(0xFFECE9E4), Color(0xFF1B1F23))
}

/** The full-window reader: just the text, following the narration; controls fade away while you read. */
@Composable
fun ReaderScreen(app: AppState, bookId: String) {
    val book by app.library.observe(bookId).collectAsState(app.library.book(bookId))
    val b = book ?: run { LaunchedEffect(Unit) { app.back() }; return }
    val text by app.reader(bookId).text.collectAsState()
    val settings by app.settingsStore.settings.collectAsState()
    val colors = readerColors(settings.readerTheme)
    val page by animateColorAsState(colors.page, tween(400))
    val ink by animateColorAsState(colors.ink, tween(400))
    val playback by app.player.state.collectAsState()
    val clock = rememberPlaybackClock(app.player, bookId, b.positionMs)
    val current = playback.bookId == bookId
    var chromeVisible by remember { mutableStateOf(true) }
    var lastMove by remember { mutableStateOf(System.currentTimeMillis()) }
    var searchOpen by remember { mutableStateOf(false) }
    var contentsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(lastMove, searchOpen, contentsOpen) {
        chromeVisible = true
        if (!searchOpen && !contentsOpen) { delay(2800); chromeVisible = false }
    }
    val chrome by animateFloatAsState(if (chromeVisible) 1f else 0f, tween(if (chromeVisible) 150 else 600))
    val t = text
    val follow = rememberFollowState(t?.paragraphAt(clock.value) ?: 0)
    val scope = rememberCoroutineScope()
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(app.searchRequests) {
        if (app.searchRequests == 0) return@LaunchedEffect
        searchOpen = true; delay(80); runCatching { searchFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(page).onPointerEvent(PointerEventType.Move) { lastMove = System.currentTimeMillis() }) {
        if (t == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        else if (t.isEmpty) ReadAlongCard(app, b, Modifier.align(Alignment.Center).widthIn(max = 560.dp), ink)
        else FollowText(t, clock, follow, { seekBook(app, bookId, it) }, Modifier.fillMaxSize(), Fonts.reading(settings.readerFont), settings.readerSize, ink,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 200.dp), maxWidth = 760.dp, anchor = .38f)

        // Top: back, title, appearance, search, contents.
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).alpha(chrome).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(page, page.copy(alpha = 0f))))
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalContentColor provides ink) {
                AppIconButton("ic_back", "Back (Esc)", { app.back() })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ink)
                    b.chapters.getOrNull(b.chapterAt(clock.value))?.let { Text(it.title, style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = .6f), maxLines = 1) }
                }
                AppIconButton("ic_search", "Search (Ctrl+F)", { if (searchOpen) searchOpen = false else app.searchRequests++ })
                if (t?.contents?.isNotEmpty() == true) AppIconButton("ic_chapters", "Contents", { contentsOpen = !contentsOpen })
                AppearanceMenu(app, ink)
            }
        }

        // Bottom: sentence and playback controls.
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp).alpha(maxOf(chrome, if (follow.following) 0f else 1f)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CompositionLocalProvider(LocalContentColor provides ink) {
                AppIconButton("ic_previous_sentence", "Previous sentence", { t?.let { sentenceJump(app, bookId, it, clock.value, -1) } }, size = 48.dp)
                Surface(modifier = Modifier.size(64.dp).pointerHoverIcon(PointerIcon.Hand), shape = CircleShape, color = ink, shadowElevation = 8.dp,
                    onClick = { if (current) app.player.toggle() else app.player.open(bookId) }) {
                    Box(contentAlignment = Alignment.Center) { CompositionLocalProvider(LocalContentColor provides page) { PlayPauseIcon(current && playback.playing, Modifier.size(26.dp)) } }
                }
                AppIconButton("ic_next_sentence", "Next sentence", { t?.let { sentenceJump(app, bookId, it, clock.value, 1) } }, size = 48.dp)
            }
        }
        ReturnToPosition(follow, Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp))

        AnimatedVisibility(searchOpen && t != null, Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 16.dp),
            enter = fadeIn() + slideInHorizontally { it / 3 }, exit = fadeOut() + slideOutHorizontally { it / 3 }) {
            SearchPanel(t ?: ReadingText.Empty, colors, searchFocus, onClose = { searchOpen = false }) { paragraph, startMs ->
                follow.following = false
                scope.launch { follow.list.animateScrollToItem(paragraph, -200) }
                if (startMs != null) seekBook(app, bookId, startMs)
            }
        }
        AnimatedVisibility(contentsOpen && t != null, Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 16.dp),
            enter = fadeIn() + slideInHorizontally { it / 3 }, exit = fadeOut() + slideOutHorizontally { it / 3 }) {
            Surface(Modifier.width(360.dp).heightIn(max = 560.dp), shape = RoundedCornerShape(Corners.large), color = colors.chrome, shadowElevation = 12.dp) {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(t?.contents.orEmpty()) { entry ->
                        Text(entry.title, Modifier.fillMaxWidth().clickable {
                            contentsOpen = false
                            if (entry.startMs != null) { follow.following = true; seekBook(app, bookId, entry.startMs) }
                            else { follow.following = false; scope.launch { follow.list.animateScrollToItem(entry.paragraph) } }
                        }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 20.dp, vertical = 12.dp), color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

fun sentenceJump(app: AppState, bookId: String, text: ReadingText, position: Long, direction: Int) {
    val starts = text.paragraphs.flatMap { p -> p.cues.mapNotNull { it.audioStartMs } }.sorted()
    val target = if (direction > 0) starts.firstOrNull { it > position + 100 } else starts.lastOrNull { it < position - 1500 } ?: starts.lastOrNull { it < position }
    target?.let { seekBook(app, bookId, it) }
}

@Composable
private fun SearchPanel(text: ReadingText, colors: ReaderColors, focus: FocusRequester, onClose: () -> Unit, onOpen: (Int, Long?) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, text) {
        if (query.trim().length < 2) emptyList() else runCatching {
            val search = PassageSearch(query.take(500))
            text.paragraphs.withIndex().flatMap { (i, p) -> search.feed(SearchPassage(i, p.segment)) }.take(50)
        }.getOrDefault(emptyList())
    }
    Surface(Modifier.width(400.dp).heightIn(max = 560.dp), shape = RoundedCornerShape(Corners.large), color = colors.chrome, shadowElevation = 12.dp) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth().background(colors.page, CircleShape).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.get("ic_search"), null, Modifier.size(16.dp), tint = colors.ink.copy(alpha = .6f))
                Spacer(Modifier.width(8.dp))
                BasicTextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onClose(); true } else false
                }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.ink), cursorBrush = SolidColor(colors.ink))
            }
            if (query.trim().length >= 2) Text(if (results.isEmpty()) "No matches in this book." else "${results.size}${if (results.size == 50) "+" else ""} matches",
                Modifier.padding(horizontal = 6.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium, color = colors.ink.copy(alpha = .6f))
            LazyColumn {
                items(results) { hit ->
                    val paragraph = text.paragraphs[hit.passage.index]
                    val startMs = paragraph.cues.firstOrNull { hit.offset in it.start until it.end }?.audioStartMs ?: paragraph.startMs
                    Column(Modifier.fillMaxWidth().clickable { onOpen(hit.passage.index, startMs) }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 6.dp, vertical = 8.dp)) {
                        Text("…" + hit.preview.trim() + "…", color = colors.ink, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (startMs != null) Text(timeText(startMs), style = MaterialTheme.typography.labelSmall, color = colors.ink.copy(alpha = .5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceMenu(app: AppState, ink: Color) {
    var open by remember { mutableStateOf(false) }
    val settings by app.settingsStore.settings.collectAsState()
    Box {
        AppIconButton("ic_font", "Text appearance", { open = true })
        DropdownMenu(open, { open = false }) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("DARK" to "Dark", "SEPIA" to "Sepia", "LIGHT" to "Light").forEach { (key, label) ->
                    FilterChip(settings.readerTheme == key, { app.settingsStore.update { it.copy(readerTheme = key) } }, { Text(label) })
                }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Size", Modifier.width(52.dp))
                OutlinedButton({ app.settingsStore.update { it.copy(readerSize = (it.readerSize - 1).coerceAtLeast(14f)) } }) { Text("A−") }
                Text("${settings.readerSize.toInt()}", Modifier.padding(horizontal = 12.dp))
                OutlinedButton({ app.settingsStore.update { it.copy(readerSize = (it.readerSize + 1).coerceAtMost(40f)) } }) { Text("A+") }
            }
            HorizontalDivider()
            Fonts.reading.forEach { (key, label) ->
                DropdownMenuItem({ Text(label, fontFamily = Fonts.reading(key)) }, { app.settingsStore.update { it.copy(readerFont = key) } },
                    trailingIcon = { if (settings.readerFont == key) Text("✓") })
            }
        }
    }
}
