@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.readalong.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import java.io.File

/** The window's content: screens slide and fade into each other; the mini player stays at the bottom. */
@Composable
fun ReadAlongApp(app: AppState) {
    val settings by app.settingsStore.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { app.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }
    var dropping by remember { mutableStateOf(false) }
    val drop = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dropping = true }
            override fun onExited(event: DragAndDropEvent) { dropping = false }
            override fun onEnded(event: DragAndDropEvent) { dropping = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropping = false
                val transferable = event.awtTransferable
                if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                @Suppress("UNCHECKED_CAST")
                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                val epub = files.singleOrNull()?.takeIf { it.extension.equals("epub", true) }
                val screen = app.screen
                if (epub != null && screen is Screen.Player) {
                    app.library.update(screen.bookId) { it.copy(epubPath = epub.path, textSource = "EPUB") }
                    app.messages.tryEmit("Matching ${epub.name} to the audio.")
                } else app.addPaths(files)
                return true
            }
        }
    }
    ReadAlongTheme(settings.accent) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = drop)) {
            Column(Modifier.fillMaxSize()) {
                AnimatedContent(app.screen, Modifier.weight(1f), transitionSpec = {
                    val deeper = targetState !is Screen.Library
                    (fadeIn(tween(240, easing = LinearOutSlowInEasing)) + scaleIn(initialScale = if (deeper) .97f else 1.02f, animationSpec = spring(dampingRatio = .9f, stiffness = 500f)))
                        .togetherWith(fadeOut(tween(160)) + scaleOut(targetScale = if (deeper) 1.01f else .98f, animationSpec = tween(160)))
                }, contentKey = { it::class to ((it as? Screen.Player)?.bookId ?: (it as? Screen.Reader)?.bookId) }) { screen ->
                    when (screen) {
                        Screen.Library -> LibraryScreen(app)
                        is Screen.Player -> PlayerScreen(app, screen.bookId)
                        is Screen.Reader -> ReaderScreen(app, screen.bookId)
                        Screen.Queue -> QueueScreen(app)
                        Screen.Settings -> SettingsScreen(app)
                    }
                }
                AnimatedVisibility(app.screen == Screen.Library || app.screen == Screen.Queue || app.screen == Screen.Settings,
                    enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    MiniPlayer(app)
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
            AnimatedVisibility(dropping, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)).padding(24.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(Corners.large), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("Drop audiobooks (or a book's EPUB) to add them", Modifier.padding(28.dp), style = MaterialTheme.typography.titleLarge, color = Color.White)
                    }
                }
            }
        }
    }
}

/** Window-wide shortcuts. Typing in a text field is left alone. */
fun handleKey(app: AppState, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val settings = app.settingsStore.value
    return when {
        event.key == Key.F && event.isCtrlPressed && app.screen is Screen.Reader -> { app.searchRequests++; true }
        event.key == Key.Escape && app.screen != Screen.Library -> { app.back(); true }
        event.key == Key.Spacebar && !event.isCtrlPressed && app.player.state.value.bookId != null -> { app.player.toggle(); true }
        event.key == Key.DirectionLeft && event.isCtrlPressed -> { app.player.skip(-settings.skipBackSeconds * 1000L); true }
        event.key == Key.DirectionRight && event.isCtrlPressed -> { app.player.skip(settings.skipForwardSeconds * 1000L); true }
        event.key == Key.DirectionLeft && app.screen !is Screen.Library -> { app.player.skip(-settings.skipBackSeconds * 1000L); true }
        event.key == Key.DirectionRight && app.screen !is Screen.Library -> { app.player.skip(settings.skipForwardSeconds * 1000L); true }
        else -> false
    }
}
