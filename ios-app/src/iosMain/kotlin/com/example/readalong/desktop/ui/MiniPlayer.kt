package com.example.readalong.desktop.ui

import com.example.readalong.compat.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.readalong.ui.components.PlayPauseIcon

/**
 * The iPhone's mini player, sized for a phone like the Android app's: the book and chapter (tap to open the
 * player), skip back, play and pause, and a thin progress line for the chapter. Speed, sleep, bookmarks and
 * chapters are in the full player, as on the phone. The desktop's dock is too wide for a phone screen.
 */
@Composable
fun MiniPlayer(app: AppState, modifier: Modifier = Modifier) {
    val state by app.player.state.collectAsState()
    val id = state.bookId ?: return
    val book by app.library.observe(id).collectAsState(app.library.book(id))
    val b = book ?: return
    val settings by app.settingsStore.settings.collectAsState()
    val cover = rememberCoverPalette(b)
    val palette = if (settings.coloredMiniPlayer) cover else accentPalette(settings.accent)
    val color by animateColorAsState(lerp(palette.background, Color(0xFF15191C), .35f), tween(500))
    val clock = rememberPlaybackClock(app.player, id, b.positionMs)
    val position = clock.value
    val chapterIndex = b.chapterAt(position)
    val chapter = b.chapters.getOrNull(chapterIndex)
    val chapterStart = chapter?.startMs ?: 0L
    val chapterEnd = b.chapters.getOrNull(chapterIndex + 1)?.startMs ?: b.durationMs
    Surface(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), shape = RoundedCornerShape(Corners.large), color = color, shadowElevation = 8.dp) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column {
                Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open the player") { app.open(Screen.Player(id)) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BookCover(b, Modifier.size(48.dp), RoundedCornerShape(Corners.small), size = 120)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(chapter?.title ?: b.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(b.title.takeIf { chapter != null }, "${hoursMinutes(((b.durationMs - position) / state.speed).toLong())} left").joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    AppIconButton("ic_rewind", "Back ${settings.skipBackSeconds} seconds", { app.player.skip(-settings.skipBackSeconds * 1000L) }, size = 44.dp, iconSize = 24.dp)
                    Surface(modifier = Modifier.size(46.dp), shape = CircleShape, color = palette.accent, onClick = { app.player.toggle() }) {
                        Box(contentAlignment = Alignment.Center) {
                            CompositionLocalProvider(LocalContentColor provides palette.background) { PlayPauseIcon(state.playing, Modifier.size(20.dp)) }
                        }
                    }
                }
                LinearProgressIndicator({ ((position - chapterStart).toFloat() / (chapterEnd - chapterStart).coerceAtLeast(1)).coerceIn(0f, 1f) },
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 8.dp).height(3.dp),
                    color = Color.White, trackColor = Color.White.copy(alpha = .18f), drawStopIndicator = {})
            }
        }
    }
}
