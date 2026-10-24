package com.example.readalong.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.readalong.R
import com.example.readalong.domain.model.Book
import com.example.readalong.playback.PlaybackState
import com.example.readalong.ui.components.BookCover

@Composable
fun MiniPlayer(book: Book, state: PlaybackState, onOpen: () -> Unit, onToggle: () -> Unit, readerTheme: String = "DARK", onDismiss: (() -> Unit)? = null) {
    val palette = com.example.readalong.ui.components.rememberCoverPalette(book)
    val settings = com.example.readalong.ui.theme.LocalAppSettings.current
    val colored = settings.coloredMiniPlayer
    val basic = com.example.readalong.ui.theme.basicPalette(settings.basicColor)
    val basicBackground = if (settings.libraryLight) basic.accent else basic.background
    val basicForeground = if (settings.libraryLight) basic.background else androidx.compose.ui.graphics.Color.White
    val background = when (readerTheme) {
        "LIGHT" -> androidx.compose.ui.graphics.Color(0xFFFAF9F5)
        "SEPIA" -> androidx.compose.ui.graphics.Color(0xFFF0E6D2)
        else -> palette.background
    }
    val foreground = if (readerTheme == "DARK") androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(0xFF242820)
    key(book.id) {
    // Springs up when a book starts playing here; already in place when returning from the player,
    // where the container transform carries it instead.
    val navigating = com.example.readalong.ui.components.LocalNavVisibilityScope.current?.transition?.let { it.currentState != it.targetState } == true
    val visible = remember(book.id) { MutableTransitionState(navigating).apply { targetState = true } }
    val dismissState = rememberSwipeToDismissBoxState(positionalThreshold = { it * .3f })
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) visible.targetState = false
    }
    LaunchedEffect(visible.currentState, visible.isIdle) {
        if (visible.isIdle && !visible.currentState) onDismiss?.invoke()
    }
    AnimatedVisibility(visibleState = visible,
        enter = slideInVertically(spring(dampingRatio = .82f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(140)),
        exit = shrinkVertically(tween(160)) + fadeOut(tween(100))) {
    SwipeToDismissBox(state = dismissState, enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = onDismiss != null, backgroundContent = {},
        modifier = Modifier.navigationBarsPadding().padding(12.dp)) {
    Surface(onClick = onOpen, modifier = Modifier.semantics {
        contentDescription = "Expand mini player"
        if (onDismiss != null) customActions = listOf(CustomAccessibilityAction("Dismiss mini player") { visible.targetState = false; true })
    }.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = if (colored) background else basicBackground, contentColor = if (colored) foreground else basicForeground, shadowElevation = 8.dp) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BookCover(book, Modifier.size(52.dp), showTitle = false)
            Column(Modifier.weight(1f)) {
                Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(state.chapterTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = if (colored) foreground.copy(alpha = .7f) else basicForeground.copy(alpha = .7f))
            }
            IconButton(onClick = onToggle) { com.example.readalong.ui.components.PlayPauseIcon(state.isPlaying, Modifier.size(28.dp), if (state.isPlaying) "Pause mini player" else "Play mini player") }
        }
    }
    }
    }
    }
}
