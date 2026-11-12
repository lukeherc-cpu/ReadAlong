@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.readalong.desktop.ui

import com.example.readalong.compat.*

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** The iPhone app's content (the desktop window's, without drag and drop or keyboard shortcuts): screens slide and fade into each other; the mini player stays at the bottom. */
@Composable
fun ReadAlongApp(app: AppState) {
    val settings by app.settingsStore.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { app.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }
    // Coming back to the computer picks up where the phone left off.
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) app.syncProgress() }
    ReadAlongTheme(settings.accent, settings.light, settings.appFont, accentOnly = settings.alwaysAccent) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).edgeSwipeBack(app)) {
            // Clear of the notch, the Dynamic Island and the home indicator; the background still reaches the edges.
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
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
                        Screen.TranscriptionSettings -> TranscriptionSettingsScreen(app)
                        Screen.PhoneSettings -> PhoneSettingsScreen(app)
                        Screen.Audible -> AudibleScreen(app)
                        Screen.Folders -> FoldersScreen(app)
                    }
                }
                AnimatedVisibility(app.screen.let { it == Screen.Library || it == Screen.Queue || it == Screen.Settings || it == Screen.TranscriptionSettings || it == Screen.PhoneSettings || it == Screen.Audible || it == Screen.Folders },
                    enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    MiniPlayer(app)
                }
            }
            FoundDialog(app)
            UpdateDialog(app)
            CoverSearchDialog(app)
            EditInfoDialog(app)
            SupportPrompt(app)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }
    }
}

/**
 * iOS's back gesture: a swipe in from the left edge goes back a screen, like the back arrow at the top. Watches in
 * the Initial pass and only consumes a swipe that started at the edge and moved right, so other gestures still work.
 */
private fun Modifier.edgeSwipeBack(app: AppState): Modifier = pointerInput(app) {
    val edge = 24.dp.toPx()
    val distance = 72.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.x > edge || app.screen == Screen.Library) return@awaitEachGesture
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (kotlin.math.abs(dy) > distance && kotlin.math.abs(dy) > dx) break
            if (dx > distance) {
                app.back()
                while (true) {
                    val rest = awaitPointerEvent(PointerEventPass.Initial)
                    rest.changes.forEach { it.consume() }
                    if (rest.changes.none { it.pressed }) break
                }
                break
            }
        }
    }
}
