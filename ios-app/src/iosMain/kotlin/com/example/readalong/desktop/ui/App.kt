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
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
