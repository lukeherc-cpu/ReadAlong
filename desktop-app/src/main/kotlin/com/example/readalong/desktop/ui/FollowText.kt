@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.readalong.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.readalong.desktop.audio.AudioPlayer
import com.example.readalong.desktop.reading.ReadingParagraph
import com.example.readalong.desktop.reading.ReadingText
import com.example.readalong.domain.reading.activeSentenceIndex
import com.example.readalong.domain.reading.sentenceAt
import com.example.readalong.domain.reading.sentenceSeekStart
import com.example.readalong.ui.reading.paragraphText
import com.example.readalong.ui.reading.rememberSentenceFade
import com.example.readalong.ui.reading.rememberSmoothFollow

/** The sound card's position for [bookId], every frame while it plays; the saved position otherwise. */
@Composable
fun rememberPlaybackClock(player: AudioPlayer, bookId: String, savedPositionMs: Long): State<Long> {
    val state by player.state.collectAsState()
    val current = state.bookId == bookId
    val clock = remember { mutableLongStateOf(if (current) player.positionNow() else savedPositionMs) }
    LaunchedEffect(current, state.playing, state.positionMs, savedPositionMs) {
        if (!current) { clock.longValue = savedPositionMs; return@LaunchedEffect }
        clock.longValue = player.positionNow()
        if (state.playing) while (true) withFrameMillis { clock.longValue = player.positionNow() }
    }
    return clock
}

class FollowState(val list: LazyListState) {
    /** Off while the reader scrolls away from the narration; "Return to current position" turns it back on. */
    var following by mutableStateOf(true)
}

@Composable
fun rememberFollowState(initialParagraph: Int): FollowState {
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = initialParagraph.coerceAtLeast(0))
    return remember(list) { FollowState(list) }
}

/**
 * The read-along text: the sentence being read is lit, and the page glides with the narration on one
 * critically damped spring (the phone's SmoothFollow). Click a sentence to listen from it. Scrolling
 * away pauses following until "Return to current position".
 */
@Composable
fun FollowText(
    text: ReadingText,
    clock: State<Long>,
    follow: FollowState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    family: FontFamily,
    fontSize: Float,
    ink: Color = Color.White,
    contentPadding: PaddingValues = PaddingValues(),
    textAlign: TextAlign = TextAlign.Start,
    anchor: Float = .32f,
    maxWidth: Dp = Dp.Unspecified,
) {
    val active by remember(text) {
        derivedStateOf {
            val position = clock.value
            val paragraph = text.paragraphAt(position)
            if (paragraph < 0) null else paragraph to activeSentenceIndex(text.paragraphs[paragraph].cues, position)
        }
    }
    val smooth = rememberSmoothFollow(follow.list)
    val layouts = remember(text) { HashMap<Int, TextLayoutResult>() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topPadding = with(density) { contentPadding.calculateTopPadding().toPx() }
    val userScroll = remember(follow) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) follow.following = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(active, follow.following, text) {
        if (!follow.following) return@LaunchedEffect
        val (index, sentence) = active ?: return@LaunchedEffect
        val info = follow.list.layoutInfo
        val target = (info.viewportSize.height * anchor)
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        val layout = layouts[index]
        val cue = text.paragraphs[index].cues.getOrNull(sentence)
        if (item == null || layout == null) {
            // Far away (opening the book, a chapter jump): place it at once, then glide from there.
            follow.list.scrollToItem(index)
            follow.list.scrollBy(-target)
            return@LaunchedEffect
        }
        val lineTop = if (cue != null && cue.start <= layout.layoutInput.text.length) layout.getLineTop(layout.getLineForOffset(cue.start.coerceAtMost(layout.layoutInput.text.length))) else 0f
        smooth.scrollBy(item.offset + lineTop - target)
    }
    val style = TextStyle(fontFamily = family, fontSize = fontSize.sp, lineHeight = (fontSize * 1.6f).sp, textAlign = textAlign)
    LazyColumn(modifier.nestedScroll(userScroll), state = follow.list, contentPadding = contentPadding, horizontalAlignment = Alignment.CenterHorizontally) {
        itemsIndexed(text.paragraphs, key = { _, p -> p.segment.id }) { index, paragraph ->
            val isActive = active?.first == index
            Box(Modifier.then(if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier).fillMaxWidth()) {
                if (paragraph.heading) Text(paragraph.segment.text, Modifier.padding(top = (fontSize * 1.4f).dp, bottom = (fontSize * .6f).dp),
                    style = style.copy(fontSize = (fontSize * 1.25f).sp, lineHeight = (fontSize * 1.7f).sp), color = ink.copy(alpha = .9f))
                else Paragraph(paragraph, if (isActive) active?.second ?: -1 else -1, isActive, text, style, ink, { layouts[index] = it }, onSeek)
            }
        }
    }
}

@Composable
private fun Paragraph(paragraph: ReadingParagraph, sentence: Int, isActive: Boolean, text: ReadingText, style: TextStyle, ink: Color,
    onLayout: (TextLayoutResult) -> Unit, onSeek: (Long) -> Unit) {
    val cue = paragraph.cues.getOrNull(sentence)
    val focus = cue?.let { it.start to it.end }
    val fade = rememberSentenceFade(focus)
    val base by animateColorAsState(ink.copy(alpha = if (isActive) .58f else .42f), tween(300))
    var hovered by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val annotated = remember(paragraph, focus, fade, base, hovered) {
        val built = paragraphText(paragraph.segment, "APP", false, emptyMap(), focus, ink, fade = fade, base = base)
        if (hovered == null || hovered == focus) built
        else androidx.compose.ui.text.AnnotatedString.Builder(built).apply {
            addStyle(SpanStyle(background = ink.copy(alpha = .07f)), hovered!!.first, hovered!!.second.coerceAtMost(built.length))
        }.toAnnotatedString()
    }
    val timed = paragraph.cues.any { it.audioStartMs != null }
    Text(annotated, Modifier.padding(vertical = (style.fontSize.value * .45f).dp)
        .then(if (timed) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
        .onPointerEvent(PointerEventType.Move) { event ->
            val l = layout ?: return@onPointerEvent
            val offset = l.getOffsetForPosition(event.changes.first().position)
            hovered = sentenceAt(paragraph.cues, offset)?.takeIf { it.audioStartMs != null }?.let { it.start to it.end }
        }
        .onPointerEvent(PointerEventType.Exit) { hovered = null }
        .pointerInput(paragraph) {
            detectTapGestures { position ->
                val l = layout ?: return@detectTapGestures
                val c = sentenceAt(paragraph.cues, l.getOffsetForPosition(position)) ?: return@detectTapGestures
                val sources = c.let { paragraph.segment.anchors.mapNotNull { a -> text.sources[a.id] } }
                sentenceSeekStart(c, sources)?.let(onSeek)
            }
        },
        color = base, style = style, onTextLayout = { layout = it; onLayout(it) })
}

/** "Return to current position", shown while the reader has scrolled away from the narration. */
@Composable
fun ReturnToPosition(follow: FollowState, modifier: Modifier = Modifier) {
    AnimatedVisibility(!follow.following, modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
        Surface(Modifier.pointerHoverIcon(PointerIcon.Hand), shape = CircleShape, color = MaterialTheme.colorScheme.primary, shadowElevation = 8.dp,
            onClick = { follow.following = true }) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.get("ic_down"), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Return to current position", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
