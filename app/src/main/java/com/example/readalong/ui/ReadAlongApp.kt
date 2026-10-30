package com.example.readalong.ui

import android.net.Uri
import kotlinx.coroutines.launch
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.readalong.domain.repository.BookRepository
import com.example.readalong.ui.about.AboutScreen
import com.example.readalong.ui.book.BookScreen
import com.example.readalong.ui.book.BookViewModel
import com.example.readalong.playback.PlaybackControls
import com.example.readalong.ui.player.PlayerScreen
import com.example.readalong.ui.player.MiniPlayer
import com.example.readalong.ui.library.LibraryScreen
import com.example.readalong.ui.library.LibraryViewModel

private object Routes {
    const val Library = "library"
    const val About = "about"
    const val Book = "book/{bookId}?fromMini={fromMini}"
}

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun ReadAlongApp(libraryViewModel: LibraryViewModel, app: com.example.readalong.ReadAlongApplication, importModel: com.example.readalong.ui.importing.ImportViewModel, showPrompts: Boolean = true) {
    val readerSettings by app.readerPreferences.preferences.collectAsStateWithLifecycle(com.example.readalong.data.preferences.ReaderPreferences())
    val appSettings = com.example.readalong.ui.theme.LocalAppSettings.current
    val bookRepository = app.bookRepository
    val playback = app.playback
    val navController = rememberNavController()
    val route by navController.currentBackStackEntryAsState()
    val view = androidx.compose.ui.platform.LocalView.current
    val activity = androidx.compose.runtime.remember(view) {
        generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }.take(16).filterIsInstance<android.app.Activity>().firstOrNull()
    }
    androidx.compose.runtime.SideEffect {
        activity?.window?.let { window ->
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
            val light = appSettings.libraryLight && (!showPrompts || route?.destination?.route != Routes.Book)
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
    }
    val actions: com.example.readalong.ui.library.BookActionsViewModel = viewModel(factory = com.example.readalong.ui.library.BookActionsViewModel.factory(app))
    val actionError by actions.error.collectAsStateWithLifecycle()
    val pendingImports by importModel.priority.collectAsStateWithLifecycle()
    val addingBook by importModel.addedBook.collectAsStateWithLifecycle()
    val preparingBooks by app.processing.queue.collectAsStateWithLifecycle(emptyList())
    val batteryPromptAnswered by androidx.compose.runtime.produceState<Boolean?>(null) {
        app.database.bookDao().observeMetadata("battery_prompt_v1").collect { value = it != null }
    }
    val power = app.getSystemService(android.os.PowerManager::class.java)
    val backgroundPromptPending = (!appSettings.batteryWarningSeen || batteryPromptAnswered == false && !power.isIgnoringBatteryOptimizations(app.packageName)) &&
        preparingBooks.any { it.state in setOf("PROCESSING", "PARTIAL_READY", "GENERATING_EPUB") }
    val importPromptPending = pendingImports.isNotEmpty() || addingBook != null
    val normalPrompts = showPrompts && appSettings.setupStage == 3
    val guideEligible = normalPrompts && !importPromptPending && batteryPromptAnswered != null && !backgroundPromptPending
    if (normalPrompts) com.example.readalong.ui.importing.ImportPriorityPrompt(importModel)
    if (normalPrompts && !importPromptPending) com.example.readalong.ui.transcription.BackgroundPreparationPrompt(app)
    val speechSetup: com.example.readalong.ui.transcription.ModelViewModel = viewModel(factory = com.example.readalong.ui.transcription.ModelViewModel.factory(app, app.speechModel, app.processing))
    val setupScope = androidx.compose.runtime.rememberCoroutineScope()
    // The book whose player the mini-player transform is opening or closing (outlives the library screen).
    var transformBook by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.example.readalong.domain.model.Book?>(null) }
    fun setupStage(stage: Int) { setupScope.launch { app.appPreferences.update { it.copy(setupStage = stage) } } }
    androidx.compose.runtime.LaunchedEffect(appSettings.setupStage) {
        // Older setups stopped at the battery step; that now shows before the first ReadAlong instead.
        if (appSettings.setupStage in 1..2) setupStage(3)
    }
    if (appSettings.setupStage == 0) {
        com.example.readalong.ui.importing.WelcomeScreen(importModel) { setupStage(3) }
        return
    }
    androidx.compose.animation.SharedTransitionLayout {
    androidx.compose.runtime.CompositionLocalProvider(com.example.readalong.ui.components.LocalNavSharedScope provides this) {
    NavHost(navController = navController, startDestination = Routes.Library) {
        // From the mini player the player slides up over the library like a sheet, and slides back
        // down on close: translation only, so it stays smooth even while the player composes.
        composable(Routes.Library,
            exitTransition = { if (targetState.openedFromMini()) androidx.compose.animation.ExitTransition.KeepUntilTransitionsFinished else androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) },
            popEnterTransition = { if (initialState.openedFromMini()) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) }) { libraryEntry ->
            androidx.compose.runtime.CompositionLocalProvider(com.example.readalong.ui.components.LocalNavVisibilityScope provides this) {
            // Ignore callbacks from outgoing destinations after their entry has been popped.
            fun open(route: String) { if (navController.currentBackStackEntry?.id == libraryEntry.id) navController.navigate(route) { launchSingleTop = true } }
            val jobs by app.processing.queue.collectAsStateWithLifecycle(emptyList())
            androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { importModel.refreshLibrary() }
            val state by libraryViewModel.state.collectAsStateWithLifecycle()
            val scanningFolder by importModel.scanningFolder.collectAsStateWithLifecycle()
            val readingFolder by importModel.readingFolder.collectAsStateWithLifecycle()
            val playbackState by playback.state.collectAsStateWithLifecycle()
            val activeBookFlow = androidx.compose.runtime.remember(playbackState.bookId) {
                playbackState.bookId?.let(bookRepository::observeBook) ?: kotlinx.coroutines.flow.flowOf(null)
            }
            val activeBook by activeBookFlow.collectAsStateWithLifecycle(null)
            var selectedId by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
            val selected = (state as? com.example.readalong.ui.library.LibraryUiState.Ready)?.books?.firstOrNull { it.id == selectedId }
            selected?.let { com.example.readalong.ui.library.BookActions(it, actions, { selectedId = null }) }
            if (selected == null && actionError != null) androidx.compose.material3.AlertDialog(
                onDismissRequest = actions::dismissError, title = { androidx.compose.material3.Text("Couldn't update book") },
                text = { androidx.compose.material3.Text(actionError!!) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = actions::dismissError) { androidx.compose.material3.Text("Close") } })
            // Mini player <-> player: the library grows a container from the mini player's bounds to the
            // full screen (tap, or follow a drag), then the real player fades in on top of it. Closing
            // a player opened this way plays it in reverse. See PlayerTransform.
            val returning = androidx.compose.runtime.remember { com.example.readalong.ui.player.PlayerSheetHandoff.closing }
            val sheet = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (returning != null) 1f else 0f) }
            var sheetBook by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(if (returning != null) transformBook else null) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (returning != null) {
                    com.example.readalong.ui.player.PlayerSheetHandoff.closing = null
                    sheet.animateTo(0f, androidx.compose.animation.core.tween(220, easing = com.example.readalong.ui.reading.ReaderMotionEasing))
                    sheetBook = null
                }
            }
            val sheetTravel = (com.example.readalong.ui.player.PlayerSheetHandoff.miniBounds?.top ?: with(androidx.compose.ui.platform.LocalDensity.current) { 600.dp.toPx() }).coerceAtLeast(1f)
            var opening by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
            fun openPlayer(book: com.example.readalong.domain.model.Book) {
                if (opening) return
                opening = true; sheetBook = book; transformBook = book
                setupScope.launch {
                    val left = 1f - sheet.value
                    sheet.animateTo(1f, androidx.compose.animation.core.tween((220 * left).toInt().coerceAtLeast(70), easing = if (sheet.value > 0f) androidx.compose.animation.core.LinearOutSlowInEasing else com.example.readalong.ui.reading.ReaderMotionEasing))
                    if (navController.currentBackStackEntry?.id == libraryEntry.id) navController.navigate("book/${Uri.encode(book.id)}?fromMini=true") { launchSingleTop = true }
                    opening = false
                }
            }
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            LibraryScreen(
                state = state,
                scanningFolder = scanningFolder,
                readingFolder = readingFolder,
                transcribing = jobs.any { it.state in setOf("QUEUED", "PROCESSING", "PARTIAL_READY", "GENERATING_EPUB") },
                onQueue = { open("queue") },
                selectedBookId = playbackState.bookId,
                onRetry = libraryViewModel::retry,
                onAbout = { open("settings") },
                onFolders = { open("folders") },
                onLongBook = { actions.dismissError(); selectedId = it.id },
                onBook = { bookId -> navController.navigate("book/${Uri.encode(bookId)}") { launchSingleTop = true } },
                importControls = { com.example.readalong.ui.importing.ImportControls(importModel,
                    onFolders = { open("folders") },
                    onBook = { navController.navigate("book/${Uri.encode(it)}") { launchSingleTop = true } }) },
                importStatus = { com.example.readalong.ui.importing.ImportStatus(importModel) },
                emptyActions = {
                    // The same choices as the welcome page, then the folder example folded under Help.
                    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        com.example.readalong.ui.importing.AudiobookSourceButtons(importModel, large = true)
                        com.example.readalong.ui.importing.FolderHelp()
                    }
                },
                miniPlayer = {
                    activeBook?.let { book -> MiniPlayer(book, playbackState, { openPlayer(book) }, playback::toggle, readerTheme = readerSettings.theme,
                        onDismiss = { setupScope.launch { playback.releaseBook(book.id) } },
                        onDrag = { dy -> if (!opening) { sheetBook = book; setupScope.launch {
                            val before = sheet.value
                            sheet.snapTo((sheet.value - dy / sheetTravel).coerceIn(0f, 1f))
                            if ((before < .3f) != (sheet.value < .3f)) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                        } } },
                        onDragEnd = { velocity ->
                            if (opening) Unit
                            else if (sheet.value > .3f || velocity < -900f) openPlayer(book)
                            else setupScope.launch { sheet.animateTo(0f, androidx.compose.animation.core.tween(160, easing = com.example.readalong.ui.reading.ReaderMotionEasing)); sheetBook = null }
                        }) }
                },
            )
            sheetBook?.let { book -> if (sheet.value > 0f) com.example.readalong.ui.player.PlayerTransform(book, readerSettings.theme, { sheet.value },
                chapterTitle = if (playbackState.bookId == book.id) playbackState.chapterTitle else "") }
            }
            com.example.readalong.ui.components.FirstVisitGuide(app, com.example.readalong.ui.components.GuidePage.HOME,
                guideEligible && selected == null && actionError == null && state is com.example.readalong.ui.library.LibraryUiState.Ready)
            }
        }
        composable("settings") { entry ->
            com.example.readalong.ui.about.SettingsScreen(app, { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() },
                { navController.navigate("speech-model") }, { navController.navigate(Routes.About) })
        }
        composable("speech-model") {
            com.example.readalong.ui.transcription.ModelScreen(speechSetup) { navController.popBackStack() }
        }
        composable("folders") { entry -> com.example.readalong.ui.importing.FoldersScreen(importModel) {
            if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack()
        } }
        composable("queue") { entry ->
            val queueModel: com.example.readalong.ui.transcription.QueueViewModel = viewModel(factory = com.example.readalong.ui.transcription.QueueViewModel.factory(app))
            com.example.readalong.ui.transcription.QueueScreen(queueModel, onBack = { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() },
                onOpen = { bookId ->
                    // Opened from that book's player: just go back to it instead of stacking another.
                    if (navController.currentBackStackEntry?.id != entry.id) Unit
                    else if (navController.previousBackStackEntry?.arguments?.getString("bookId") == bookId) navController.popBackStack()
                    else navController.navigate("book/${Uri.encode(bookId)}") { launchSingleTop = true }
                })
        }
        composable(Routes.About) {
            AboutScreen(onBack = { navController.popBackStack() }, onSpeechModel = { navController.navigate("speech-model") })
        }
        composable(Routes.Book, arguments = listOf(navArgument("bookId") { type = NavType.StringType }, navArgument("fromMini") { type = NavType.BoolType; defaultValue = false }),
            enterTransition = { if (targetState.openedFromMini()) androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(110)) else androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) },
            popExitTransition = { if (initialState.openedFromMini()) androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90)) else androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) }) { entry ->
            androidx.compose.runtime.CompositionLocalProvider(com.example.readalong.ui.components.LocalNavVisibilityScope provides this) {
            val bookId = requireNotNull(entry.arguments?.getString("bookId"))
            val fromMini = entry.arguments?.getBoolean("fromMini") == true
            fun closeFromMini() { if (fromMini) com.example.readalong.ui.player.PlayerSheetHandoff.closing = bookId }
            val model: BookViewModel = viewModel(factory = BookViewModel.factory(bookId, bookRepository))
            val reading: com.example.readalong.ui.reading.ReadingViewModel = viewModel(factory = com.example.readalong.ui.reading.ReadingViewModel.factory(bookId, app.database, app.transcriptRepository, app.readerPreferences, app.generatedEpub, app.processing, app.importedEpub, app.bookReaderPreferences, !fromMini && appSettings.openingView == "READ_ONLY"))
            val frameLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
            val readerFrame = androidx.compose.runtime.remember { com.example.readalong.ui.reading.ReaderTransitionFrame(frameLayer) }
            var mode by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(if (!fromMini && appSettings.openingView in setOf("FULLSCREEN", "READ_ONLY")) "follow" else "player") }
            fun setReaderMode(value: String) {
                if (value != mode) { readerFrame.frozen = android.animation.ValueAnimator.areAnimatorsEnabled(); mode = value }
            }
            androidx.activity.compose.BackHandler(enabled = mode != "player") { setReaderMode("player") }
            androidx.activity.compose.BackHandler(enabled = mode == "player" && fromMini) { closeFromMini(); navController.popBackStack() }
            val state by model.state.collectAsStateWithLifecycle()
            val busy by model.busy.collectAsStateWithLifecycle()
            val updateFailed by model.updateFailed.collectAsStateWithLifecycle()
            val playbackState by playback.state.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect((state as? com.example.readalong.ui.book.BookUiState.Ready)?.book?.id) {
                (state as? com.example.readalong.ui.book.BookUiState.Ready)?.book?.let { book ->
                    playback.select(book)
                    if (reading.readOnly.value) playback.pause()
                }
            }
            if (state is com.example.readalong.ui.book.BookUiState.Ready) {
                val book = (state as com.example.readalong.ui.book.BookUiState.Ready).book
                val displayState = if (playbackState.bookId == book.id) playbackState else com.example.readalong.playback.PlaybackState(book.id, book.positionMs, book.durationMs ?: 0, isLoading = true)
                com.example.readalong.ui.components.FirstVisitGuide(app,
                    if (mode == "follow") com.example.readalong.ui.components.GuidePage.READER else com.example.readalong.ui.components.GuidePage.PLAYER,
                    guideEligible)
                val readerPalette = com.example.readalong.ui.components.rememberCoverPalette(book)
                val surfaceState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                // Opened from the mini player: the mini player grows into this screen. Opened from the
                // library: the cover flies from its tile into the player.
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                androidx.compose.runtime.CompositionLocalProvider(com.example.readalong.ui.components.LocalPlayerCoverKey provides
                    if (fromMini) null else com.example.readalong.ui.components.coverKey(bookId)) {
                androidx.compose.animation.SharedTransitionLayout {
                val shared = this
                val bookReading by reading.readOnly.collectAsStateWithLifecycle()
                val plainPreferences by reading.bookPreferences.collectAsStateWithLifecycle()
                val readerBackground = if (bookReading) { when (plainPreferences.theme) { "DARK" -> androidx.compose.ui.graphics.Color.Black; "SEPIA" -> com.example.readalong.ui.reading.SepiaPaper; else -> androidx.compose.ui.graphics.Color.White } } else when (readerSettings.theme) { "LIGHT" -> androidx.compose.ui.graphics.Color(0xFFFAF9F5); "SEPIA" -> com.example.readalong.ui.reading.SepiaPaper; "BLACK" -> androidx.compose.ui.graphics.Color.Black; else -> readerPalette.background }
                // Color and corners share the bounds' duration and curve, and are only read at draw time:
                // reading them here recomposed the entire player or reader on every transition frame.
                val transformColor = animateColorAsState(if (mode == "follow") readerBackground else com.example.readalong.ui.reading.minimizedReadAlongColor(readerSettings.theme, readerPalette.preview),
                    androidx.compose.animation.core.tween(com.example.readalong.ui.reading.ReaderMotionMillis, easing = com.example.readalong.ui.reading.ReaderMotionEasing), label = "Reader color")
                val radius = animateDpAsState(if (mode == "follow") 0.dp else 24.dp,
                    androidx.compose.animation.core.tween(com.example.readalong.ui.reading.ReaderMotionMillis, easing = com.example.readalong.ui.reading.ReaderMotionEasing), label = "Reader corners")
                val surfaceColorOf = androidx.compose.runtime.remember(transformColor) { { transformColor.value } }
                val clipShape = androidx.compose.runtime.remember(radius) { com.example.readalong.ui.reading.AnimatedCornerShape { radius.value } }
                androidx.compose.animation.AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                    }, label = "Expand reader",
                ) { visibleMode ->
                val visibility = this
                val moving = shared.isTransitionActive || visibility.transition.currentState != visibility.transition.targetState
                androidx.compose.runtime.LaunchedEffect(moving) { if (!moving) readerFrame.frozen = false }
                val surface = with(shared) {
                    androidx.compose.ui.Modifier.sharedBounds(rememberSharedContentState("reader:$bookId"), visibility,
                        enter = androidx.compose.animation.EnterTransition.None, exit = androidx.compose.animation.ExitTransition.None,
                        resizeMode = androidx.compose.animation.SharedTransitionScope.ResizeMode.RemeasureToBounds,
                        zIndexInOverlay = if (visibleMode == mode) 1f else 0f,
                        boundsTransform = { _, _ -> androidx.compose.animation.core.tween(com.example.readalong.ui.reading.ReaderMotionMillis, easing = com.example.readalong.ui.reading.ReaderMotionEasing) },
                        clipInOverlayDuringTransition = OverlayClip(clipShape))
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    com.example.readalong.ui.reading.LocalReaderDrawFrame provides (visibleMode == mode),
                    com.example.readalong.ui.reading.LocalReaderFrame provides readerFrame,
                    com.example.readalong.ui.reading.LocalReaderSurface provides surface,
                    com.example.readalong.ui.reading.LocalReaderSurfaceColor provides surfaceColorOf,
                    com.example.readalong.ui.reading.LocalReaderInsets provides com.example.readalong.ui.reading.ReaderInsets(24.dp, 56.dp, 24.dp),
                    com.example.readalong.ui.reading.LocalReaderTransforming provides moving) {
                surfaceState.SaveableStateProvider(visibleMode) {
                if (visibleMode == "follow") {
                    com.example.readalong.ui.reading.FollowScreen(book, displayState, playback, reading, { setReaderMode("player") })
                } else {
                    PlayerScreen(book, displayState, playback, { closeFromMini(); navController.popBackStack() }, model::setFinished, reading,
                        { navController.navigate("speech-model") }, { reading.preloadReader(displayState.positionMs); setReaderMode("follow") },
                        onFolders = { navController.navigate("folders") { launchSingleTop = true } },
                        onQueue = { navController.navigate("queue") { launchSingleTop = true } })
                }
                }
                }
                }
                }
                }
                }
            } else if (fromMini && state is com.example.readalong.ui.book.BookUiState.Loading) {
                // The book is already playing; it loads within a frame or two. Keep the sliding sheet plain.
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background))
            } else {
                BookScreen(state, busy, updateFailed, { navController.popBackStack() }, model::retry, model::setFinished)
            }
            }
        }
    }
    }
    }
}

private fun androidx.navigation.NavBackStackEntry.openedFromMini() = arguments?.getBoolean("fromMini") == true
