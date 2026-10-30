package com.example.readalong.ui.reading

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import com.example.readalong.domain.reading.sentenceCues
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.example.readalong.R
import com.example.readalong.domain.model.Book
import com.example.readalong.playback.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.example.readalong.ui.components.ReadAlongSearchField
import com.example.readalong.ui.components.SearchCountLabel
import com.example.readalong.ui.components.SearchProgressLine
import com.example.readalong.ui.components.SearchResultCard
import com.example.readalong.ui.components.highlightMatches
import com.example.readalong.ui.components.smoothWheelScroll

/** One reading surface, sharing the service-owned audio player in both modes. */
@Composable
fun FollowScreen(book: Book, state: PlaybackState, playback: PlaybackControls, model: ReadingViewModel, onPlayer: () -> Unit) {
    val appSettings = com.example.readalong.ui.theme.LocalAppSettings.current
    val readingOnly by model.readOnly.collectAsStateWithLifecycle()
    val followPreferences by model.preferences.collectAsStateWithLifecycle()
    val bookPreferences by model.bookPreferences.collectAsStateWithLifecycle()
    val preferences = if (readingOnly) bookPreferences else followPreferences
    val palette = com.example.readalong.ui.components.rememberCoverPalette(book)
    val plainBackground = when (preferences.theme) { "DARK" -> Color.Black; "SEPIA" -> SepiaPaper; else -> Color.White }
    val plainInk = when (preferences.theme) { "DARK" -> Color.White; "SEPIA" -> SepiaInk; else -> Color.Black }
    val pagePalette = if (preferences.theme == "SEPIA") SepiaPalette else palette
    val colors = if (readingOnly) {
        // Read only is deliberately monochrome; a grey palette fills every other role (no Material purple).
        val grey = com.example.readalong.ui.components.CoverPalette(Color(0xFF111111), Color(0xFF4A4A4A), Color(0xFFE5E5E5))
        if (preferences.theme == "DARK") com.example.readalong.ui.theme.readAlongScheme(false, grey).copy(background = plainBackground, surface = plainBackground, onSurface = plainInk, onBackground = plainInk, primary = Color.White, onPrimary = Color.Black, secondaryContainer = Color(0xFF333333), onSecondaryContainer = Color.White)
        else if (preferences.theme == "SEPIA") com.example.readalong.ui.theme.readAlongScheme(true, SepiaPalette, plainBackground).copy(onSurface = plainInk, onBackground = plainInk, primary = plainInk, onPrimary = plainBackground, secondaryContainer = Color(0xFFE2D5BC), onSecondaryContainer = plainInk)
        else com.example.readalong.ui.theme.readAlongScheme(true, grey, plainBackground).copy(onSurface = plainInk, onBackground = plainInk, primary = Color.Black, onPrimary = Color.White, secondaryContainer = Color(0xFFE5E5E5), onSecondaryContainer = Color.Black)
    } else if (preferences.theme == "DARK") com.example.readalong.ui.theme.playerScheme(palette, palette.background).copy(onSurface = Color.White, onBackground = Color.White, secondaryContainer = palette.preview, onSecondaryContainer = Color.White)
    // "Dark": the same black as Read only's dark page, with the cover's accent for controls.
    else if (preferences.theme == "BLACK") com.example.readalong.ui.theme.playerScheme(palette, Color.Black).copy(background = Color.Black, surface = Color.Black, onSurface = Color.White, onBackground = Color.White, secondaryContainer = Color(0xFF333333), onSecondaryContainer = Color.White)
    // Sepia tones its controls with the page (no cover purple on brown paper); Light keeps the cover's accent.
    else com.example.readalong.ui.theme.readAlongScheme(true, pagePalette).copy(
        primary = pagePalette.preview, onPrimary = Color.White,
        background = if (preferences.theme == "SEPIA") SepiaPaper else Color(0xFFFAF9F5),
        surface = if (preferences.theme == "SEPIA") SepiaPaper else Color(0xFFFAF9F5),
        onSurface = Color(0xFF242820), onBackground = Color(0xFF242820))
    // Search accent: the cover's bright accent on dark pages, its deeper tone on light and sepia pages.
    val darkPage = preferences.theme == "DARK" || preferences.theme == "BLACK"
    val searchAccent = if (darkPage) palette.accent else pagePalette.preview
    // The spoken sentence turns the page's strong ink (no highlight box): white on Cover and Dark,
    // near-black on Light, dark brown on Sepia; the rest of the text stays muted.
    val (spokenInk, restInk) = when (preferences.theme) {
        "LIGHT" -> Color(0xFF14181B) to Color(0xFF7D8682)
        "SEPIA" -> SepiaInk to Color(0xFF9C8B72)
        else -> Color.White to Color.White.copy(alpha = .52f)
    }
    MaterialTheme(colorScheme = colors) {
        val readerFont = readingFont(preferences.fontFamily)
        val items = model.readerPages.collectAsLazyPagingItems()
        var retainedPage by remember(book.id) { mutableStateOf<androidx.paging.ItemSnapshotList<com.example.readalong.domain.model.TranscriptSegment>?>(null) }
        val page = items.itemSnapshotList
        SideEffect { if (page.items.isNotEmpty()) retainedPage = page }
        val fallbackPage = retainedPage.takeIf { page.items.isEmpty() && items.loadState.refresh is LoadState.Loading }
        val renderedCount = if (fallbackPage != null) fallbackPage.size else items.itemCount
        val frame = LocalReaderFrame.current
        val transforming = LocalReaderTransforming.current || frame?.frozen == true
        var contentReady by remember(book.id) { mutableStateOf(false) }
        val start by model.readerStart.collectAsStateWithLifecycle()

        val audio by model.readingAudio.collectAsStateWithLifecycle()
        val hasReadingAudio by model.readingHasAudio.collectAsStateWithLifecycle()
        val importedEdition by model.importedEdition.collectAsStateWithLifecycle()
        val epubFonts by produceState<Map<String, FontFamily>>(emptyMap(), importedEdition?.editionId) {
            value = emptyMap()
            importedEdition?.editionId?.let { value = model.epubFonts(it) }
        }
        val nearby by model.passages.collectAsStateWithLifecycle()
        val sources = remember(nearby) { nearby.filter { !it.imported && it.anchors.isEmpty() }.associateBy { it.id } }
        LaunchedEffect(state.positionMs / 12_000) { model.follow(state.positionMs) }
        val chapters by model.contents.collectAsStateWithLifecycle()
        val version by model.version.collectAsStateWithLifecycle()
        val error by model.error.collectAsStateWithLifecycle()
        val correction by model.correction.collectAsStateWithLifecycle()
        val manualSync by model.manualSync.collectAsStateWithLifecycle()
        val syncBusy by model.syncBusy.collectAsStateWithLifecycle()
        var fullScreen by rememberSaveable(book.id) { mutableStateOf(true) }
        var chromeVisible by rememberSaveable(book.id) { mutableStateOf(!state.isPlaying) }
        var controlTap by remember { mutableLongStateOf(0L) }
        val view = androidx.compose.ui.platform.LocalView.current
        val textDensity = androidx.compose.ui.platform.LocalDensity.current
        val activity = remember(view) {
            generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
                .take(16).filterIsInstance<android.app.Activity>().firstOrNull()
        }
        DisposableEffect(fullScreen, activity) {
            val controller = activity?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
            if (fullScreen) {
                controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
        }
        SideEffect {
            activity?.window?.let { window ->
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = readingOnly && preferences.theme != "DARK"
                controller.isAppearanceLightNavigationBars = readingOnly && preferences.theme != "DARK"
            }
        }
        androidx.activity.compose.BackHandler(onBack = onPlayer)
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(error, fullScreen) {
            // Read once: the error can be cleared elsewhere while the snackbar is showing.
            val message = error
            if (message != null && manualSync == null) {
                kotlinx.coroutines.withTimeoutOrNull(5_000) { snackbar.showSnackbar(message, duration = SnackbarDuration.Indefinite) }
                snackbar.currentSnackbarData?.dismiss()
                model.clearError(message)
            }
        }
        val scope = rememberCoroutineScope()
        val searchResults by model.searchResults.collectAsStateWithLifecycle()
        val searching by model.searching.collectAsStateWithLifecycle()
        val searchError by model.searchError.collectAsStateWithLifecycle()
        val searchNotice by model.searchNotice.collectAsStateWithLifecycle()
        var searchQuery by rememberSaveable(book.id) { mutableStateOf("") }
        var following by rememberSaveable(book.id) { mutableStateOf(true) }
        var userScrolled by remember { mutableStateOf(false) }
        var untranscribedChapter by remember { mutableStateOf<ReadingChapter?>(null) }
        var panel by remember { mutableStateOf<String?>(null) }
        val currentAudioPosition by rememberUpdatedState(state.positionMs)
        val immersive by rememberUpdatedState(fullScreen)
        val controlsShown by rememberUpdatedState(chromeVisible)
        LaunchedEffect(fullScreen, chromeVisible, controlTap, panel, manualSync, correction) {
            if (fullScreen && chromeVisible && panel == null && manualSync == null && correction == null) {
                kotlinx.coroutines.delay(3_000)
                chromeVisible = false
            }
        }
        val cutoutTop = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
        val followInsetPx = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.roundToPx() }
        val list = rememberLazyListState()
        val follow = rememberSmoothFollow(list)
        val layouts = remember { mutableStateMapOf<Int, TextLayoutResult>() }
        val paragraphTops = remember { mutableStateMapOf<Int, Float>() }
        var surfaceTop by remember { mutableFloatStateOf(0f) }
        // Paragraph tops are measured against the page itself (not the screen), so they stay right while
        // the page's bounds are animating.
        val surfaceCoordinates = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
        // The live page is shown as soon as it is positioned at the preview's line, even while the
        // card is still growing: the text is then already there and simply revealed by the bounds.
        val reveal by androidx.compose.animation.core.animateFloatAsState(if (contentReady) 1f else 0f, tween(120), label = "Reader text handoff")
        // Controls fade in with the expansion instead of appearing at its end.
        val chromeIn = remember(book.id) { androidx.compose.animation.core.Animatable(if (frame?.frozen == true) 0f else 1f) }
        LaunchedEffect(book.id) { chromeIn.animateTo(1f, tween(ReaderMotionMillis, delayMillis = ReaderMotionMillis / 3, easing = ReaderMotionEasing)) }
        // Read only until the page is revealed: reading a paragraph's top here recomposed the whole
        // reader on every frame of a scroll, which a phone busy making a ReadAlong can't keep up with.
        val handoffLayout = if (contentReady) null else layouts[start?.index]
        val handoffTop = if (contentReady) null else paragraphTops[start?.index]
        // Keyed on the refresh state too: while a ReadAlong is being made or matched to an EPUB, every
        // chunk invalidates the pager, so a refresh is often in flight when the page lays out. Without
        // this key the page was never revealed and the unscrollable stand-in preview stayed on top.
        LaunchedEffect(start?.generation, handoffLayout, handoffTop, items.loadState.refresh is LoadState.NotLoading) {
            if (contentReady || items.loadState.refresh !is LoadState.NotLoading) return@LaunchedEffect
            val target = start ?: return@LaunchedEffect
            if (items.itemCount == 0) { contentReady = true; return@LaunchedEffect }
            val layout = layouts[target.index] ?: return@LaunchedEffect
            val top = paragraphTops[target.index] ?: return@LaunchedEffect
            val anchor = frame?.previewAnchor
            if (following && anchor != null && items.peek(target.index)?.id == anchor.paragraphId) {
                val line = layout.getLineForOffset(anchor.character.coerceIn(0, layout.layoutInput.text.length))
                val delta = top + layout.getLineTop(line) - anchor.y
                if (kotlin.math.abs(delta) > 1f) list.scrollBy(delta)
            }
            // Do not reveal a paragraph until the initial anchor has been laid out at its destination.
            withFrameNanos { }
            contentReady = true
        }
        // Last resort: never leave the stand-in over the page. Text that keeps changing underneath can
        // stop the anchored handoff from completing; show the live page as it is.
        LaunchedEffect(start?.generation) {
            if (start == null) return@LaunchedEffect
            kotlinx.coroutines.delay(1_500)
            snapshotFlow { items.itemCount > 0 || items.loadState.refresh is LoadState.NotLoading }.first { it }
            contentReady = true
        }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { it?.let(model::export) }
        LaunchedEffect(book.id) { if (model.readerStart.value == null) model.preloadReader(state.positionMs) }
        LaunchedEffect(version?.activeEditionId) {
            if (start != null && start?.imported != true && start?.generated != true && version?.activeEditionId != null) model.openReader(if (readingOnly || !following) audio else state.positionMs, resume = readingOnly)
        }
        LaunchedEffect(importedEdition?.editionId, importedEdition?.preference) {
            val edition = importedEdition
            val current = start
            // An initial null emission must not reopen an already prepared pager and reset
            // the visual anchor after the expansion. Explicit removal also opens its pager.
            if (edition != null && current != null &&
                (current.imported != (readingOnly || edition.preference != "GENERATED") ||
                    current.imported && current.editionId != edition.editionId)) {
                model.openReader(if (readingOnly) audio else state.positionMs, resume = readingOnly)
            }
        }
        LaunchedEffect(state.isPlaying) {
            chromeVisible = !state.isPlaying
            if (state.isPlaying && !readingOnly) { following = true; panel = null }
        }
        var positioned by rememberSaveable(book.id) { mutableIntStateOf(-1) }
        var pendingTapSeek by remember { mutableStateOf<Long?>(null) }
        var tapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        fun sentencePressed(paragraph: com.example.readalong.domain.model.TranscriptSegment, offset: Int) {
            tapJob?.cancel()
            if (readingOnly || immersive && !controlsShown) {
                chromeVisible = true
                return
            }
            tapJob = scope.launch {
                val target = model.sentenceTarget(paragraph, offset) ?: return@launch
                pendingTapSeek = target
                following = true; userScrolled = false
                playback.listenFrom(target)
                if (immersive) chromeVisible = false
            }
        }
        LaunchedEffect(start?.generation, state.positionMs / 1000, following, readingOnly, items.itemCount, layouts[start?.index], panel, fullScreen, contentReady) {
            val target = start ?: return@LaunchedEffect
            if (items.itemCount == 0) return@LaunchedEffect
            if (positioned != target.generation) {
                val index = target.index.coerceIn(0, items.itemCount - 1)
                val paragraph = if (index < items.itemCount) items.peek(index) else null
                val expectedSize = preferences.fontSize * if (paragraph?.formatting?.kind?.startsWith("H") == true) 1.25f else 1f
                val columnPx = frame?.previewWidthPx?.takeIf { it > 0 } ?: view.width
                val quoteInset = if (paragraph?.formatting?.kind in listOf("QUOTE", "LIST")) 16.dp else 0.dp
                val expectedWidth = columnPx - with(textDensity) { (preferences.margin.dp * 2 + quoteInset).roundToPx() }
                val layout = layouts[index]?.takeIf {
                    it.layoutInput.style.fontSize == expectedSize.sp &&
                        it.layoutInput.style.fontFamily == readerFont &&
                        kotlin.math.abs(it.layoutInput.constraints.maxWidth - expectedWidth) <= 2 &&
                        it.layoutInput.style.lineHeight == (preferences.fontSize * preferences.lineSpacing).sp
                }
                if ((target.characterOffset > 0 || target.lineOffsetPx > 0) && layout == null) {
                    // First make an offscreen target visible so Paging/Text can measure it.
                    // Do not mark it positioned until this same effect applies its character offset.
                    if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.requestScrollToItem(index)
                    return@LaunchedEffect
                }
                val offset = if (target.characterOffset > 0 && layout != null)
                    layout.getLineTop(layout.getLineForOffset(target.characterOffset.coerceAtMost(layout.layoutInput.text.length))).toInt() else 0
                // One request owns both paragraph and character positioning. Separate effects
                // could race and reset a retained pager to the paragraph's opening line.
                val lineHeight = layout?.let {
                    val line = it.getLineForOffset(target.characterOffset.coerceAtMost(it.layoutInput.text.length))
                    (it.getLineBottom(line) - it.getLineTop(line)).toInt().coerceAtLeast(1)
                }
                list.requestScrollToItem(index, offset + target.lineOffsetPx.coerceIn(0, lineHeight?.minus(1) ?: Int.MAX_VALUE))
                positioned = target.generation
            } else if (contentReady && following && !readingOnly && panel == null) {
                pendingTapSeek?.let { expected ->
                    if (state.positionMs !in expected..(expected + 2000)) return@LaunchedEffect
                    pendingTapSeek = null
                    return@LaunchedEffect
                }
                val timed = nearby.any { passage ->
                    passage.imported == target.imported && if (passage.anchors.isEmpty()) state.positionMs in passage.startMs until passage.endMs
                    else passage.anchors.any { state.positionMs in it.startMs until it.endMs }
                }
                if (!timed) return@LaunchedEffect // Keep the last passage through music or unprepared intervals.
                val requested = model.indexAt(state.positionMs)
                // A database lookup can suspend while switching editions empties the pager.
                val count = items.itemCount
                if (count == 0 || start?.generation != target.generation) return@LaunchedEffect
                val index = requested.coerceIn(0, count - 1)
                if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                    if (kotlin.math.abs(index - list.firstVisibleItemIndex) <= 8) list.animateScrollToItem(index)
                    else list.scrollToItem(index)
                }
                // A long paragraph can span several screens. Follow its active anchor's line, too.
                val paragraph = if (index < items.itemCount) items.peek(index) else null
                val anchor = paragraph?.anchors?.firstOrNull { state.positionMs in it.startMs until it.endMs }
                val layout = layouts[index]
                val visible = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (paragraph != null && layout != null && visible != null && (anchor != null || !paragraph.imported && state.positionMs in paragraph.startMs until paragraph.endMs)) {
                    val sentence = sentenceCues(paragraph, sources).let { cues -> cues.getOrNull(com.example.readalong.domain.reading.activeSentenceIndex(cues, state.positionMs)) } ?: return@LaunchedEffect
                    val character = spokenCharacter(timedCharacters(paragraph, sources), sentence, state.positionMs)
                    val line = layout.getLineForOffset(character.coerceIn(0, layout.layoutInput.text.length))
                    val top = visible.offset + layout.getLineTop(line)
                    val bottom = visible.offset + layout.getLineBottom(line)
                    // The toolbar occupies layout space above this list; adding its height again
                    // would immediately undo a correctly aligned expansion anchor.
                    val safeTop = list.layoutInfo.viewportStartOffset
                    if (top < safeTop || bottom > list.layoutInfo.viewportEndOffset - followInsetPx / 2) {
                        val delta = top - (safeTop + followInsetPx)
                        follow.scrollBy(delta.toFloat())
                    }
                }
            }
        }
        val scroll = remember { object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) { following = false; userScrolled = true }
                return Offset.Zero
            }
        } }
        LaunchedEffect(list.isScrollInProgress) {
            if (!list.isScrollInProgress) userScrolled = false
        }
        LaunchedEffect(readingOnly, start?.generation, userScrolled, list, items) {
            if (readingOnly && userScrolled && start?.bookMode == true) snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
                .distinctUntilChanged().collect { (index, offset) ->
                    val paragraph = if (index < items.itemCount) items.peek(index) else null
                    if (paragraph != null) {
                        val character = layouts[index]?.getOffsetForPosition(Offset(0f, offset.toFloat())) ?: 0
                        val anchor = paragraph.anchorAt(character)
                        model.saveBookViewport(paragraph, index, character)
                    }
                }
        }
        fun moveSentence(direction: Int) {
            tapJob?.cancel()
            tapJob = scope.launch {
                val target = model.adjacentSentence(state.positionMs, direction) ?: return@launch
                pendingTapSeek = target; following = true; userScrolled = false
                playback.seek(target)
            }
        }
        fun skipFive(direction: Int) {
            tapJob?.cancel(); pendingTapSeek = null; following = true; userScrolled = false
            playback.skip(if (direction < 0) -appSettings.readerBack * 1000L else appSettings.readerForward * 1000L)
        }
        var switchingMode by remember { mutableStateOf(false) }
        fun changeMode(bookMode: Boolean) {
            if (bookMode == readingOnly || switchingMode || positioned != start?.generation) return
            tapJob?.cancel(); panel = null
            switchingMode = true
            playback.pause()
            // Capture the displayed line in either direction, not the old listening clock.
            // The same character survives font/margin changes between the two presentations.
            val index = list.firstVisibleItemIndex
            val paragraph = if (index < items.itemCount) items.peek(index) else null
            val layout = layouts[index]
            val offset = list.firstVisibleItemScrollOffset
            val line = layout?.getLineForVerticalPosition(offset.toFloat())
            val character = line?.let { layout?.getLineStart(it) } ?: 0
            val viewport = paragraph?.let { ReaderViewport(index, character,
                line?.let { (offset - (layout?.getLineTop(it) ?: 0f)).toInt().coerceAtLeast(0) } ?: offset) }
            following = false; pendingTapSeek = null; userScrolled = false; chromeVisible = true
            scope.launch {
                try {
                    paragraph?.let { model.saveBookViewport(it, index, character) }
                    model.setReadOnly(bookMode, currentAudioPosition, viewport)
                    if (!bookMode && paragraph != null && paragraph.startMs >= 0) {
                        model.sentenceTarget(paragraph, character, notifyWhenUnavailable = false)?.let(playback::seek)
                    }
                } finally {
                    switchingMode = false
                }
            }
        }
        // Change ink and paper together. Animating only paper briefly paints white ink
        // on the previous white page when returning from book mode to dark ReadAlong.
        val surfaceColor = colors.background
        val surfaceColorOf = LocalReaderSurfaceColor.current
        // Read only also morphs its paper from the card's color, instead of turning white at the first frame.
        val animatedSurface = transforming
        Box(LocalReaderSurface.current.fillMaxSize().drawBehind {
            drawRect((if (animatedSurface) surfaceColorOf().takeIf { it != Color.Unspecified } else null) ?: surfaceColor)
        }.onGloballyPositioned { surfaceTop = it.positionInRoot().y; surfaceCoordinates[0] = it }) {
        if (start != null) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val fullWidth = with(density) { view.width.toDp() }
        val fullHeight = with(density) { view.height.toDp() }
        // Keep the preview's column width while revealing the taller page. Glyphs never rewrap
        // at the handoff; the user's margins apply inside the same centered reading column.
        val columnWidth = frame?.previewWidthPx?.takeIf { it > 0 }?.let { with(density) { it.toDp() } } ?: fullWidth
        val columnInset = ((fullWidth - columnWidth) / 2).coerceAtLeast(0.dp)
        Scaffold(modifier = Modifier.wrapContentSize(androidx.compose.ui.Alignment.TopCenter, unbounded = true)
            .requiredSize(fullWidth, fullHeight).pointerInput(book.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (!change.pressed) {
                            if (!moved && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) controlTap++
                            break
                        }
                    }
                }
            }.graphicsLayer { alpha = reveal }
            .retainReaderFrame(frame.takeIf { reveal == 1f && it?.frozen != true }), containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            AnimatedVisibility(visible = !readingOnly && (!fullScreen || chromeVisible),
                enter = fadeIn(tween(ModeSwitchMillis - 60)) + slideInVertically(tween(ModeSwitchMillis, easing = ReaderMotionEasing)) { it },
                exit = fadeOut(tween(ModeSwitchMillis - 60)) + slideOutVertically(tween(ModeSwitchMillis, easing = ReaderMotionEasing)) { it }) {
            Surface(color = colors.surface, contentColor = colors.onSurface, tonalElevation = 3.dp, modifier = Modifier.graphicsLayer { alpha = chromeIn.value }) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick = { moveSentence(-1) }, modifier = Modifier.size(52.dp)) { Icon(painterResource(R.drawable.ic_previous_sentence), "Previous sentence", Modifier.size(28.dp)) }
                        TextButton(onClick = { skipFive(-1) }, contentPadding = PaddingValues(4.dp)) {
                            Icon(painterResource(R.drawable.ic_rewind), "Back ${appSettings.readerBack} seconds", Modifier.size(28.dp)); Text("${appSettings.readerBack}s")
                        }
                        FilledIconButton(onClick = playback::toggle, modifier = Modifier.size(64.dp)) { com.example.readalong.ui.components.PlayPauseIcon(state.isPlaying, Modifier.size(34.dp)) }
                        TextButton(onClick = { skipFive(1) }, contentPadding = PaddingValues(4.dp)) {
                            Text("${appSettings.readerForward}s"); Icon(painterResource(R.drawable.ic_forward), "Forward ${appSettings.readerForward} seconds", Modifier.size(28.dp))
                        }
                        IconButton(onClick = { moveSentence(1) }, modifier = Modifier.size(52.dp)) { Icon(painterResource(R.drawable.ic_next_sentence), "Next sentence", Modifier.size(28.dp)) }
                }
            }
            }
        }) { insets ->
            Box(Modifier.fillMaxSize().then(if (fullScreen) Modifier else Modifier.padding(insets)).pointerInput(fullScreen, chromeVisible) {
                // Observe taps without intercepting the text's sentence seek or long-press gestures.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (!change.pressed) {
                            if (!change.isConsumed && !moved && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                                tapJob?.cancel()
                                chromeVisible = true
                            }
                            break
                        }
                    }
                }
            }.then(if (fullScreen && !chromeVisible) Modifier.semantics {
                onClick("Show reading controls") { tapJob?.cancel(); chromeVisible = true; true }
            }.testTag("reader_reveal_controls") else Modifier)) {
            Column(Modifier.fillMaxSize()) {
                if (items.loadState.refresh is LoadState.Error) TextButton(onClick = items::retry) { Text("Retry loading text") }
                else if (renderedCount == 0 && items.loadState.refresh !is LoadState.Loading) Text(version?.message ?: "Your ReadAlong is being made. Text appears here as it's ready; you can keep listening.", Modifier.padding(24.dp))
                LazyColumn(Modifier.weight(1f).nestedScroll(scroll).smoothWheelScroll(list) { following = false; userScrolled = true }.testTag("follow_passages"), state = list,
                    contentPadding = PaddingValues(start = preferences.margin.dp + columnInset, end = preferences.margin.dp + columnInset,
                        top = 64.dp + cutoutTop, bottom = if (readingOnly) 24.dp else 100.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (renderedCount == 0 && items.loadState.refresh is LoadState.Loading) item { PassageSkeleton() }
                    items(renderedCount, key = { index ->
                        val paragraph = if (fallbackPage != null) fallbackPage[index] else if (index < items.itemCount) items.peek(index) else null
                        paragraph?.id ?: "unloaded:$index"
                    }) { index ->
                        val paragraph = if (fallbackPage != null) fallbackPage[index] else if (index < items.itemCount) items[index] else null
                        if (paragraph != null) {
                            val active = paragraph.anchors.firstOrNull { state.positionMs in it.startMs until it.endMs }
                            val cues = remember(paragraph, sources) { sentenceCues(paragraph, sources) }
                            val sentence = cues.getOrNull(com.example.readalong.domain.reading.activeSentenceIndex(cues, state.positionMs))
                            val activeSingle = sentence != null
                            val focus = sentence?.let { it.start to it.end } ?: (0 to 0)
                            val shownFocus = focus.takeIf { !readingOnly && (active != null || activeSingle) }
                            val fade = rememberSentenceFade(shownFocus)
                            val styled = paragraphText(paragraph, preferences.fontFamily, preferences.useEpubFonts, epubFonts,
                                shownFocus, spokenInk, Color.Transparent, fade, restInk)
                            DisposableEffect(index) { onDispose { layouts.remove(index); paragraphTops.remove(index) } }
                            Text(styled, modifier = Modifier.fillMaxWidth().testTag("reader_paragraph").onGloballyPositioned { coordinates ->
                                val page = surfaceCoordinates[0]?.takeIf { it.isAttached && coordinates.isAttached }
                                val pageTop = page?.localPositionOf(coordinates, androidx.compose.ui.geometry.Offset.Zero)?.y ?: (coordinates.positionInRoot().y - surfaceTop)
                                paragraphTops[index] = pageTop
                                val focusStart = shownFocus?.first
                                val layout = layouts[index]
                                if (focusStart != null && layout != null && frame != null && !frame.frozen) {
                                    val line = layout.getLineForOffset(focusStart.coerceIn(0, layout.layoutInput.text.length))
                                    frame.readerAnchor = ReaderVisualAnchor(paragraph.id, focusStart, pageTop + layout.getLineTop(line))
                                }
                            }.padding(start = if (paragraph.formatting?.kind in listOf("QUOTE", "LIST")) 16.dp else 0.dp).semantics {
                                selected = !readingOnly && (active != null || activeSingle)
                                onLongClick(if (paragraph.imported) "Set sync point" else "Correct this passage") {
                                    if (readingOnly) { chromeVisible = true } else if (paragraph.imported) { playback.pause(); following = false; model.editSync(paragraph, currentAudioPosition) }
                                    else model.editPassage(active?.id ?: paragraph.anchors.firstOrNull()?.id ?: paragraph.id)
                                    true
                                }
                                onClick(if (paragraph.startMs < 0) "Select this reading passage" else if (readingOnly) "Select audio position in this paragraph" else "Listen from this paragraph") {
                                    sentencePressed(paragraph, 0); true
                                }
                            }.pointerInput(paragraph, readingOnly) {
                                detectTapGestures(onLongPress = { point ->
                                    val offset = layouts[index]?.getOffsetForPosition(point) ?: 0
                                    if (readingOnly) { chromeVisible = true } else if (paragraph.imported) { playback.pause(); following = false; model.editSync(paragraph, currentAudioPosition) }
                                    else model.editPassage(paragraph.anchorAt(offset)?.id ?: paragraph.id)
                                }) { point ->
                                    sentencePressed(paragraph, layouts[index]?.getOffsetForPosition(point) ?: 0)
                                }
                            }, onTextLayout = { layouts[index] = it }, fontFamily = readerFont,
                                color = if (readingOnly) MaterialTheme.colorScheme.onBackground else restInk,
                                fontSize = (preferences.fontSize * if (paragraph.formatting?.kind?.startsWith("H") == true) 1.25f else 1f).sp,
                                fontWeight = if (paragraph.formatting?.kind?.startsWith("H") == true) FontWeight.Bold else FontWeight.Normal,
                                textAlign = if (paragraph.formatting?.centered == true || paragraph.formatting?.kind == "BREAK") TextAlign.Center else TextAlign.Start,
                                lineHeight = (preferences.fontSize * preferences.lineSpacing).sp)
                        } else PassageSkeleton()
                    }
                    if (items.loadState.append is LoadState.Error) item { TextButton(onClick = items::retry) { Text("Retry loading text") } }
                }
            }
            if (panel == "search") Box(Modifier.fillMaxSize().padding(top = 64.dp + cutoutTop).background(colors.surface)) {
                    // Same look as the library search: accent progress line, a result count and rising cards.
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        SearchProgressLine(searching, searchAccent, Modifier.padding(top = 4.dp, bottom = 6.dp))
                        if (searchQuery.isNotBlank()) searchNotice?.let { Text(it, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium) }
                        searchError?.let { Text(it, Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium) }
                        if (searchQuery.isNotBlank() && !searching && searchError == null) SearchCountLabel(searchResults.size, capped = searchResults.size >= 20)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                            itemsIndexed(searchResults, key = { _, it -> "${it.segment.id}:${it.offset}" }) { index, result ->
                                val chapterTitle = chapters.firstOrNull { it.id == result.segment.chapterId }?.title
                                val meta = listOfNotNull(chapterTitle, result.audioMs?.let { clockTime(it) } ?: "No audio match").joinToString(" · ")
                                SearchResultCard(highlightMatches(result.preview, searchQuery, searchAccent), meta, index, readerFont, onClick = { scope.launch {
                                    if (model.selectSearch(result)) {
                                        tapJob?.cancel(); pendingTapSeek = null; playback.pause()
                                        // ReadAlong page: tap a result and the narration plays from that sentence.
                                        if (!readingOnly && model.readingHasAudio.value) { playback.listenFrom(model.readingAudio.value); following = true }
                                        else following = false
                                        if (!model.readingHasAudio.value) model.setReadOnly(true, currentAudioPosition)
                                        userScrolled = false; panel = null
                                    }
                                } })
                            }
                        }
                    }
                }
            AnimatedVisibility(visible = !fullScreen || chromeVisible, modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).graphicsLayer { alpha = chromeIn.value },
                enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it },
                exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { -it }) {

                androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
                Row(Modifier.fillMaxWidth().background(colors.surface).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onPlayer) { Icon(painterResource(R.drawable.ic_back), "Player") }
                    IconButton(onClick = { panel = "contents" }) { Icon(painterResource(R.drawable.ic_chapters), "Contents") }
                    if (panel != "search") Box(Modifier.weight(1f), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        ReaderModeSwitch(readingOnly, ::changeMode)
                    }
                    if (panel == "search") ReadAlongSearchField(searchQuery, { searchQuery = it.take(500); model.search(searchQuery) },
                        onClose = { panel = null; searchQuery = ""; model.search("") }, accent = searchAccent,
                        modifier = Modifier.weight(5f).padding(horizontal = 4.dp), testTag = "reader_search")
                    else IconButton(onClick = { panel = "search" }) { Icon(painterResource(R.drawable.ic_search), "Search text") }
                    IconButton(onClick = { panel = "appearance" }) { Icon(painterResource(R.drawable.ic_settings), "Settings") }
                }
                }

            }
            val syncBottom by androidx.compose.animation.core.animateDpAsState(if (readingOnly) 16.dp else 112.dp, tween(ModeSwitchMillis, easing = ReaderMotionEasing), label = "Sync progress position")
            EpubSyncProgress(model, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = syncBottom))
            AnimatedVisibility(!following && !readingOnly, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(horizontal = 12.dp).padding(bottom = 104.dp),
                enter = fadeIn(tween(160)) + androidx.compose.animation.scaleIn(tween(220, easing = ReaderMotionEasing), initialScale = .9f),
                exit = fadeOut(tween(120)) + androidx.compose.animation.scaleOut(tween(160), targetScale = .9f)) {
                FilledTonalButton(colors = ButtonDefaults.filledTonalButtonColors(containerColor = palette.preview, contentColor = Color.White),
                    onClick = { following = true }) { Text("Return to current position") }
            }

            }
        }
        }
        // Read only's page shows the book's own text at its own position: the ReadAlong preview
        // can't stand in for it, so that page simply fades in over its paper.
        if (reveal < 1f && !readingOnly) {
            // Until the page is ready, a live copy of the preview stands in: same column width and the
            // same line position as the card, so as the bounds grow its text simply continues below
            // (no blank area), and the page then crossfades in over it line for line.
            val previewWidth = frame?.previewWidthPx?.takeIf { it > 0 }?.let { with(androidx.compose.ui.platform.LocalDensity.current) { it.toDp() } }
            var landed by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - reveal }, contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                // For the frame or two before the stand-in has scrolled to the line, show the card's last frame.
                if (!landed && frame?.recorded == true) FrozenReaderFrame(frame, Modifier.fillMaxSize())
                androidx.compose.runtime.CompositionLocalProvider(LocalReaderFrame provides null) {
                    FollowCard(state, model, { chromeVisible = true }, surfaceColor, visible = true,
                        modifier = (if (previewWidth != null) Modifier.width(previewWidth).fillMaxHeight() else Modifier.fillMaxSize()).graphicsLayer { alpha = if (landed) 1f else 0f },
                        square = false, showExpand = false,
                        colorOf = if (animatedSurface) surfaceColorOf else null, landOn = frame?.previewAnchor, onLanded = { landed = true })
                }
            }
        }
        }
        if (panel == "bookmarks") com.example.readalong.ui.player.BookmarksDialog(model, if (readingOnly) audio else state.positionMs, playback::seek, { panel = null })
        manualSync?.let { draft -> ManualSyncDialog(draft, currentAudioPosition, syncBusy, error,
            onDismiss = model::dismissSync, onSave = model::saveSync) }
        correction?.let { draft ->
            var replacement by remember(draft) { mutableStateOf(draft.text) }
            AlertDialog(onDismissRequest = model::dismissCorrection, title = { Text("Correct passage") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The text made from the audio is kept, so you can undo this. It changes only this passage.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(replacement, { replacement = it.take(8000) }, label = { Text("Corrected text") }, modifier = Modifier.heightIn(max = 240.dp))
                    TextButton(onClick = { model.saveCorrection(draft.original, restore = true) }) { Text("Undo my correction") }
                }
            }, confirmButton = { TextButton(enabled = replacement.isNotBlank(), onClick = { model.saveCorrection(replacement) }) { Text("Save") } },
                dismissButton = { TextButton(onClick = model::dismissCorrection) { Text("Cancel") } })
        }
        if (panel == "appearance") ReaderAppearance(preferences, { panel = null }, bookMode = readingOnly) { if (readingOnly) model.bookSettings(it) else model.settings(it); panel = null }
        untranscribedChapter?.let { chapter ->
            AlertDialog(onDismissRequest = { untranscribedChapter = null }, title = { Text("No text yet") },
                text = { Text("${chapter.title} hasn't been transcribed yet. Listen to it without the text?") },
                confirmButton = { TextButton(onClick = {
                    untranscribedChapter = null; following = true; userScrolled = false
                    playback.seek(chapter.startMs); playback.play()
                }) { Text("Listen only") } },
                dismissButton = { TextButton(onClick = { untranscribedChapter = null }) { Text("Cancel") } })
        }
        if (panel == "contents") AlertDialog(onDismissRequest = { panel = null }, title = { Text("Contents") }, text = {
            LazyColumn { items(chapters, key = { it.id }) { chapter -> TextButton(enabled = if (readingOnly) chapter.readable else chapter.readerIndex != null || chapter.startMs >= 0, onClick = {
                if (chapter.readerIndex != null) {
                    playback.pause(); model.openEpubSection(chapter.readerIndex)
                    if (!readingOnly && chapter.startMs >= 0) playback.seek(chapter.startMs)
                    following = false; userScrolled = false
                } else if (readingOnly) { model.openReader(chapter.startMs); userScrolled = false }
                else if (chapter.status != "Ready" && !chapter.status.startsWith("Partly") && chapter.status != "Available") untranscribedChapter = chapter
                else {
                    // Go to the chapter with its first sentence lit; the narration waits for Play.
                    scope.launch {
                        playback.pause(); playback.seek(chapter.startMs)
                        following = true; userScrolled = false
                        model.openReaderAt(chapter.startMs)
                    }
                }; panel = null
            }) { Column(Modifier.fillMaxWidth()) {
                Text(chapter.title)
                if (!readingOnly) Text(chapter.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } } } }
        }, confirmButton = { TextButton(onClick = { panel = null }) { Text("Close") } })
    }
}

private fun com.example.readalong.domain.model.TranscriptSegment.anchorAt(offset: Int) =
    anchors.firstOrNull { if (imported) offset >= it.start && offset < it.end else offset < it.end }

/** Sepia paper and its dark brown ink, shared by ReadAlong and Read only. */
internal val SepiaPaper = Color(0xFFF0E6D2)
internal val SepiaInk = Color(0xFF3A2611)
/** Brown controls for sepia pages: deep ink, a mid brown for filled buttons and a tan for containers. */
internal val SepiaPalette = com.example.readalong.ui.components.CoverPalette(SepiaInk, Color(0xFF7A5A38), Color(0xFFD9C6A3))
