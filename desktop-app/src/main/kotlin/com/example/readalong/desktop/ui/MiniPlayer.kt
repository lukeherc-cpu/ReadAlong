package com.example.readalong.desktop.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.readalong.ui.components.PlayPauseIcon

/** The bar at the bottom of the library: the current book, its progress and quick controls. */
@Composable
fun MiniPlayer(app: AppState, modifier: Modifier = Modifier) {
    val state by app.player.state.collectAsState()
    val id = state.bookId ?: return
    val book by app.library.observe(id).collectAsState(app.library.book(id))
    val b = book ?: return
    val palette = rememberCoverPalette(b)
    val color by animateColorAsState(lerp(palette.background, Color(0xFF15191C), .25f), tween(500))
    val clock = rememberPlaybackClock(app.player, id, b.positionMs)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val settings by app.settingsStore.settings.collectAsState()
    Surface(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).hoverable(interaction), shape = RoundedCornerShape(Corners.large),
        color = if (hovered) lerp(color, Color.White, .04f) else color, shadowElevation = 16.dp) {
        Column {
            Row(Modifier.clickable { app.open(Screen.Player(id)) }.pointerHoverIcon(PointerIcon.Hand).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(b, Modifier.size(52.dp), RoundedCornerShape(Corners.small), size = 120)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val chapter = b.chapters.getOrNull(b.chapterAt(clock.value))?.title
                    Text(listOfNotNull(chapter, "${timeText(clock.value)} / ${timeText(b.durationMs)}").joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    AppIconButton("ic_rewind", "Back ${settings.skipBackSeconds} seconds", { app.player.skip(-settings.skipBackSeconds * 1000L) })
                    Surface(Modifier.size(46.dp).pointerHoverIcon(PointerIcon.Hand), shape = CircleShape, color = palette.accent, onClick = { app.player.toggle() }) {
                        Box(contentAlignment = Alignment.Center) {
                            CompositionLocalProvider(LocalContentColor provides palette.background) { PlayPauseIcon(state.playing, Modifier.size(20.dp)) }
                        }
                    }
                    AppIconButton("ic_forward", "Forward ${settings.skipForwardSeconds} seconds", { app.player.skip(settings.skipForwardSeconds * 1000L) })
                    Spacer(Modifier.width(8.dp))
                    Text(speedLabel(state.speed), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = .8f),
                        modifier = Modifier.clip(CircleShape).clickable { app.player.setSpeed(nextSpeed(state.speed)) }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp, vertical = 6.dp))
                    VolumeControl(app)
                }
            }
            ProgressLine(clock.value.toFloat() / b.durationMs.coerceAtLeast(1), Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp), color = palette.accent, height = 3.dp)
        }
    }
}

private fun nextSpeed(speed: Float) = listOf(1f, 1.25f, 1.5f, 1.75f, 2f).firstOrNull { it > speed + .01f } ?: 1f

@Composable
private fun VolumeControl(app: AppState) {
    val settings by app.settingsStore.settings.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(150.dp).padding(start = 6.dp, end = 10.dp)) {
        Icon(if (settings.volume < .01f) androidx.compose.material.icons.Icons.Rounded.VolumeOff else androidx.compose.material.icons.Icons.Rounded.VolumeUp,
            "Volume", Modifier.size(18.dp).clickable { app.player.setVolume(if (settings.volume < .01f) 1f else 0f) }, tint = Color.White.copy(alpha = .8f))
        Spacer(Modifier.width(6.dp))
        SeekBar(settings.volume, { app.player.setVolume(it) }, Modifier.weight(1f), color = Color.White.copy(alpha = .85f))
    }
}
