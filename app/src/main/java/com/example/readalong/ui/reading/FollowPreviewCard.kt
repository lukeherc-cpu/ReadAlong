package com.example.readalong.ui.reading

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.R
import com.example.readalong.domain.model.TranscriptSegment
import com.example.readalong.domain.reading.sentenceCues
import com.example.readalong.domain.reading.activeSentenceIndex
import com.example.readalong.playback.PlaybackState

internal const val NotStartedMessage = "Text isn't available at this position yet. You can make one from the back of the book's cover in the player. You can check on the progress of the transcript by tapping the book logo on the home page.\n\nYou can keep listening."

internal fun displayedPassages(passages: List<TranscriptSegment>): List<TranscriptSegment> = when {
    passages.any { it.imported } -> passages.filter { it.imported }
    passages.any { it.anchors.isNotEmpty() } -> passages.filter { it.anchors.isNotEmpty() }
    else -> passages
}

internal fun currentPassage(passages: List<TranscriptSegment>, position: Long): TranscriptSegment? {
    val sources = passages.filter { !it.imported && it.anchors.isEmpty() }.associateBy { it.id }
    val rows = displayedPassages(passages).flatMap { p -> sentenceCues(p, sources).map { p to it } }
    return rows.getOrNull(activeSentenceIndex(rows.map { it.second }, position))?.first
}

/** Confirmed sentences follow saved words; estimated gaps advance proportionally without changing the sentence highlight. */
internal fun timedCharacters(paragraph: TranscriptSegment, sources: Map<String, TranscriptSegment>) =
    paragraph.anchors.ifEmpty {
        listOf(com.example.readalong.domain.model.TranscriptAnchor(paragraph.id, paragraph.startMs, paragraph.endMs, 0, paragraph.text.length))
    }.flatMap { anchor ->
        com.example.readalong.domain.reading.timedAnchorWords(paragraph.text, anchor,
            sources[anchor.id] ?: paragraph.takeIf { it.id == anchor.id && it.anchors.isEmpty() })
    }

internal fun spokenCharacter(words: List<Pair<IntRange, com.example.readalong.domain.model.TranscriptWord>>,
    cue: com.example.readalong.domain.reading.SentenceCue, position: Long): Int {
    if (cue.estimated && cue.audioStartMs != null && cue.audioEndMs != null && cue.audioEndMs > cue.audioStartMs) {
        val fraction = ((position - cue.audioStartMs).toDouble() / (cue.audioEndMs - cue.audioStartMs)).coerceIn(0.0, 1.0)
        return (cue.start + ((cue.end - cue.start) * fraction).toInt()).coerceAtMost((cue.end - 1).coerceAtLeast(cue.start))
    }
    return words.lastOrNull { (range, word) -> range.first >= cue.start && range.last < cue.end && word.startMs <= position }?.first?.first ?: cue.start
}

@Composable
fun ActiveSentence(state: PlaybackState, model: ReadingViewModel) {
    val passages by model.passages.collectAsStateWithLifecycle()
    val shown = remember(passages) { displayedPassages(passages) }
    val sources = remember(passages) { passages.filter { !it.imported && it.anchors.isEmpty() }.associateBy { it.id } }
    val cues = remember(shown, sources) { shown.flatMap { p -> sentenceCues(p, sources).map { p to it } } }
    LaunchedEffect(state.positionMs / 12_000) { model.follow(state.positionMs) }
    val active = cues.getOrNull(activeSentenceIndex(cues.map { it.second }, state.positionMs))
    val sentence = active?.let { (p, cue) -> p.text.substring(cue.start, cue.end).replace('\n', ' ') }.orEmpty()
    val words = remember(active?.first, sources) { active?.first?.let { timedCharacters(it, sources) }.orEmpty() }
    val character = active?.let { (_, cue) -> spokenCharacter(words, cue, state.positionMs) - cue.start } ?: 0
    FittedSentenceLine(sentence, character)
}

@Composable
internal fun FittedSentenceLine(sentence: String, character: Int, onPhrase: (String) -> Unit = {}) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = MaterialTheme.typography.titleMedium
    var width by remember { mutableIntStateOf(0) }
    val phrases = remember(sentence, width, style, measurer) {
        com.example.readalong.domain.reading.fittingPhrases(sentence) { measurer.measure(it, style, softWrap = false).size.width <= width }
    }
    val range = phrases.lastOrNull { it.first <= character } ?: phrases.firstOrNull()
    val phrase = range?.let { sentence.substring(it) }.orEmpty()
    val measured = remember(phrase, style, measurer) { measurer.measure(phrase, style, softWrap = false).size.width }
    // A single unusually long word stays complete by shrinking only that word's line.
    val size = if (width > 0 && measured > width) style.fontSize * (width.toFloat() / measured) else style.fontSize
    LaunchedEffect(phrase) { if (phrase.isNotBlank()) onPhrase(phrase) }
    Text(phrase, Modifier.fillMaxWidth().onSizeChanged { width = it.width }.testTag("active_sentence"),
        color = Color.White, style = style, fontSize = size, maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center)

}

/** Preview and reader share paragraph formatting, so expanding preserves the sentence context. */
@Composable
fun FollowCard(state: PlaybackState, model: ReadingViewModel, onExpand: () -> Unit, color: Color = MaterialTheme.colorScheme.secondaryContainer, onCover: (() -> Unit)? = null, visible: Boolean = true, modifier: Modifier = Modifier, square: Boolean = true, showExpand: Boolean = true,
    colorOf: (() -> Color)? = null, landOn: ReaderVisualAnchor? = null, onLanded: () -> Unit = {}) {
    val frame = LocalReaderFrame.current
    val insets = LocalReaderInsets.current
    val ink = if (color == SepiaPaper) SepiaInk else if (color.luminance() > .35f) Color(0xFF182A3B) else Color.White
    val preferences by model.preferences.collectAsStateWithLifecycle()
    val family = if (preferences.previewFont == "MATCH") preferences.fontFamily else preferences.previewFont
    val importedEdition by model.importedEdition.collectAsStateWithLifecycle()
    val epubFonts by produceState<Map<String, androidx.compose.ui.text.font.FontFamily>>(emptyMap(), importedEdition?.editionId) {
        importedEdition?.editionId?.let { value = model.epubFonts(it) }
    }
    val passages by model.passages.collectAsStateWithLifecycle()
    val job by model.processingJob.collectAsStateWithLifecycle()
    // No ReadAlong has been started for this book yet: say how to make one.
    val notStarted = job == null || job?.state == "WAITING_SELECTION"
    LaunchedEffect(state.positionMs / 12_000) { model.follow(state.positionMs) }
    val shown = remember(passages) { displayedPassages(passages) }
    val sources = remember(passages) { passages.filter { !it.imported && it.anchors.isEmpty() }.associateBy { it.id } }
    val rows = shown
    var retained by remember(model.bookId) { mutableStateOf(rows) }
    LaunchedEffect(rows) { if (rows.isNotEmpty()) retained = rows }
    val displayed = rows.ifEmpty { retained }
    val activeIndex = displayed.indexOf(currentPassage(passages, state.positionMs))
    val activeKey = displayed.getOrNull(activeIndex)?.id
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val follow = rememberSmoothFollow(list)
    var viewport by remember { mutableIntStateOf(0) }
    val layouts = remember { mutableStateMapOf<String, androidx.compose.ui.text.TextLayoutResult>() }
    val active = displayed.getOrNull(activeIndex)
    val words = remember(active, sources) { active?.let { timedCharacters(it, sources) }.orEmpty() }
    val cue = active?.let { paragraph -> sentenceCues(paragraph, sources).let { cues -> cues.getOrNull(activeSentenceIndex(cues, state.positionMs)) } }
    val character = cue?.let { spokenCharacter(words, it, state.positionMs) } ?: 0
    val layout = activeKey?.let { layouts[it] }
    val line = layout?.getLineForOffset(character.coerceIn(0, layout.layoutInput.text.length))
    var positionedKey by remember { mutableStateOf<String?>(null) }
    var cardTop by remember { mutableFloatStateOf(0f) }
    val rowTops = remember { mutableStateMapOf<String, Float>() }
    LaunchedEffect(activeKey, character, layout, rowTops[activeKey], cardTop) {
        if (frame?.frozen != true && activeKey != null && layout != null) {
            val y = rowTops[activeKey] ?: return@LaunchedEffect
            frame?.previewAnchor = ReaderVisualAnchor(activeKey, character, y - cardTop + layout.getLineTop(layout.getLineForOffset(character.coerceIn(0, layout.layoutInput.text.length))))
        }
    }
    val cardDensity = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(activeKey, line, viewport, visible) {
        if (frame?.frozen == true || activeIndex < 0 || viewport == 0) return@LaunchedEffect
        val lineTop = if (line != null) layout!!.getLineTop(line) else 0f
        if (positionedKey != activeKey) {
            // Back from the full page: land the spoken line where it was on the page, so the page's
            // last frame and this card line up.
            val insetTop = with(cardDensity) { insets.top.toPx() }
            // ([landOn]: a stand-in card that must start exactly where another card left the line.)
            val pageY = (landOn ?: frame?.readerAnchor)?.takeIf { positionedKey == null && it.paragraphId == activeKey }?.y?.minus(insetTop)
                ?.takeIf { it in 0f..viewport * .8f }
            val offset = lineTop.toInt() - (pageY?.toInt() ?: (viewport / 5))
            val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
            // The next paragraph is usually already on screen: glide to it on the same spring.
            if (positionedKey != null && visible && row != null) follow.scrollBy(row.offset + lineTop - viewport / 5)
            else if (positionedKey != null && visible) list.animateScrollToItem(activeIndex, offset)
            else list.scrollToItem(activeIndex, offset)
            positionedKey = activeKey
            onLanded()
        } else if (line != null) {
            val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
            if (row != null) {
                val top = row.offset + lineTop
                val bottom = row.offset + layout!!.getLineBottom(line)
                if (top < 0 || bottom > viewport * .75f) {
                    if (visible) follow.scrollBy(top - viewport / 5)
                    else list.scrollToItem(activeIndex, lineTop.toInt() - viewport / 5)
                }
            }
        }
    }
    // Read only's page has other text on other paper, so its frame can't morph into the card: the card
    // keeps its own text and fades it in while the paper shrinks back to the card's color.
    val bookMode by model.readOnly.collectAsStateWithLifecycle()
    val pageFrame = frame.takeIf { !bookMode }
    val textIn = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(frame?.frozen, bookMode) {
        if (bookMode && frame?.frozen == true) {
            textIn.snapTo(0f)
            textIn.animateTo(1f, androidx.compose.animation.core.tween(ReaderMotionMillis * 2 / 3, delayMillis = ReaderMotionMillis / 3, easing = ReaderMotionEasing))
        } else textIn.snapTo(1f)
    }
    // After the card shrinks back from the full page, the page's last frame fades out over the live card.
    val handoff = remember { androidx.compose.animation.core.Animatable(0f) }
    var wasFrozen by remember { mutableStateOf(false) }
    LaunchedEffect(frame?.frozen) {
        if (frame?.frozen == true) wasFrozen = true
        else if (wasFrozen) { wasFrozen = false; handoff.snapTo(1f); handoff.animateTo(0f, androidx.compose.animation.core.tween(240)) }
    }
    val cardShape = androidx.compose.foundation.shape.RoundedCornerShape(com.example.readalong.ui.theme.Corners.large)
    // During expand/minimize the card's color animates; draw it (draw phase) instead of recomposing for it.
    val surface = if (colorOf != null) Modifier.clip(cardShape).drawBehind { drawRect(colorOf().takeIf { it != Color.Unspecified } ?: color) } else Modifier
    Card(onClick = onCover ?: onExpand, modifier = modifier.fillMaxWidth().then(if (square) Modifier.aspectRatio(1f) else Modifier.fillMaxHeight()).then(surface).testTag("follow_card"), shape = cardShape, colors = CardDefaults.cardColors(containerColor = if (colorOf != null) Color.Transparent else color)) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val retainedSize = pageFrame?.layer?.size
        val fixed = if (pageFrame?.frozen == true && retainedSize != null && retainedSize.width > 0)
            with(density) { Modifier.wrapContentSize(Alignment.TopCenter, unbounded = true).requiredSize(retainedSize.width.toDp(), retainedSize.height.toDp()) } else Modifier
        Box(Modifier.fillMaxSize().then(fixed).onSizeChanged { if (square && frame?.frozen != true) frame?.previewWidthPx = it.width }.onGloballyPositioned { cardTop = it.positionInRoot().y }.retainReaderFrame(pageFrame, LocalReaderDrawFrame.current) { handoff.value }.graphicsLayer { alpha = textIn.value }) {
            androidx.compose.foundation.lazy.LazyColumn(state = list, userScrollEnabled = false,
                modifier = Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom).onSizeChanged { viewport = it.height }.testTag("follow_preview"),
                contentPadding = PaddingValues(horizontal = preferences.margin.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (displayed.isEmpty() && !notStarted) item { Text("Text isn't available at this position yet. You can keep listening.", color = ink.copy(alpha = .7f)) }
                items(displayed.size, key = { displayed[it].id }) { index ->
                    val paragraph = displayed[index]
                    val cues = remember(paragraph, sources) { sentenceCues(paragraph, sources) }
                    val sentence = cues.getOrNull(activeSentenceIndex(cues, state.positionMs))
                    val focus = sentence?.let { it.start to it.end }
                    val text = paragraphText(paragraph, family, preferences.useEpubFonts, epubFonts,
                        focus, ink, fade = rememberSentenceFade(focus), base = ink.copy(alpha = .72f))
                    DisposableEffect(paragraph.id) { onDispose { layouts.remove(paragraph.id); rowTops.remove(paragraph.id) } }
                    Text(text, modifier = Modifier.fillMaxWidth().padding(start = if (paragraph.formatting?.kind in listOf("QUOTE", "LIST")) 16.dp else 0.dp)
                        .onGloballyPositioned { rowTops[paragraph.id] = it.positionInRoot().y },
                        color = ink.copy(alpha = .72f), fontFamily = readingFont(family),
                        fontSize = (preferences.fontSize * if (paragraph.formatting?.kind?.startsWith("H") == true) 1.25f else 1f).sp,
                        fontWeight = if (paragraph.formatting?.kind?.startsWith("H") == true) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                        textAlign = if (paragraph.formatting?.centered == true || paragraph.formatting?.kind == "BREAK") androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
                        lineHeight = (preferences.fontSize * preferences.lineSpacing).sp,
                        onTextLayout = { layouts[paragraph.id] = it })
                }
            }
            // The longer how-to always fits the card: it shrinks its text to the space between the buttons.
            if (displayed.isEmpty() && notStarted) androidx.compose.foundation.text.BasicText(NotStartedMessage,
                Modifier.fillMaxSize().padding(top = maxOf(insets.top, 56.dp), bottom = insets.bottom + 16.dp, start = maxOf(preferences.margin.dp, 16.dp), end = maxOf(preferences.margin.dp, 16.dp)),
                style = MaterialTheme.typography.bodyLarge.copy(color = ink.copy(alpha = .78f), lineHeight = androidx.compose.ui.unit.TextUnit(1.35f, androidx.compose.ui.unit.TextUnitType.Em)),
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 17.sp, stepSize = .5.sp))
            if (showExpand) IconButton(onClick = onExpand, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(painterResource(R.drawable.ic_fullscreen), "Read in full screen", tint = ink)
            }
        }
    }
}
