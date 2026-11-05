package com.example.readalong.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.readalong.desktop.core.Bookmark
import com.example.readalong.ui.components.PlayPauseIcon

/**
 * The mini player: a dock along the bottom of the library, queue and settings, like a desktop music app.
 * Left, the book and chapter (click to open the player); centre, chapter skip, skip back and forward, play
 * and the chapter's seek bar; right, speed, sleep, bookmark, chapters and volume. The player itself is unchanged.
 */
@Composable
fun MiniPlayer(app: AppState, modifier: Modifier = Modifier) {
    val state by app.player.state.collectAsState()
    val id = state.bookId ?: return
    val book by app.library.observe(id).collectAsState(app.library.book(id))
    val b = book ?: return
    val palette = rememberCoverPalette(b)
    val color by animateColorAsState(lerp(palette.background, Color(0xFF15191C), .35f), tween(500))
    val clock = rememberPlaybackClock(app.player, id, b.positionMs)
    val settings by app.settingsStore.settings.collectAsState()
    val position = clock.value
    val chapterIndex = b.chapterAt(position)
    val chapter = b.chapters.getOrNull(chapterIndex)
    val chapterStart = chapter?.startMs ?: 0L
    val chapterEnd = b.chapters.getOrNull(chapterIndex + 1)?.startMs ?: b.durationMs
    Surface(modifier.fillMaxWidth().height(112.dp), color = color) {
        Box(Modifier.fillMaxSize()) {
            HorizontalDivider(Modifier.align(Alignment.TopCenter), color = Color.White.copy(alpha = .08f))
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    // The book: click to open the player.
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(Corners.medium))
                            .background(if (hovered) Color.White.copy(alpha = .05f) else Color.Transparent)
                            .hoverable(interaction).clickable(onClickLabel = "Open the player") { app.open(Screen.Player(id)) }
                            .pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        BookCover(b, Modifier.size(56.dp), RoundedCornerShape(Corners.small), size = 120)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(chapter?.title ?: b.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(b.title.takeIf { chapter != null }, "${hoursMinutes(((b.durationMs - position) / state.speed).toLong())} left").joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // Transport and the chapter's seek bar.
                    Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                            VectorButton(Icons.Rounded.SkipPrevious, "Previous chapter", enabled = b.chapters.isNotEmpty()) {
                                // Like a music player: back to this chapter's start, or the previous chapter if already near it.
                                val target = if (position - chapterStart > 3_000 || chapterIndex <= 0) chapterStart else b.chapters[chapterIndex - 1].startMs
                                seekBook(app, id, target)
                            }
                            AppIconButton("ic_rewind", "Back ${settings.skipBackSeconds} seconds", { app.player.skip(-settings.skipBackSeconds * 1000L) }, size = 44.dp, iconSize = 26.dp)
                            Surface(modifier = Modifier.size(52.dp).pointerHoverIcon(PointerIcon.Hand), shape = CircleShape, color = palette.accent, shadowElevation = 6.dp, onClick = { app.player.toggle() }) {
                                Box(contentAlignment = Alignment.Center) {
                                    CompositionLocalProvider(LocalContentColor provides palette.background) { PlayPauseIcon(state.playing, Modifier.size(22.dp)) }
                                }
                            }
                            AppIconButton("ic_forward", "Forward ${settings.skipForwardSeconds} seconds", { app.player.skip(settings.skipForwardSeconds * 1000L) }, size = 44.dp, iconSize = 26.dp)
                            VectorButton(Icons.Rounded.SkipNext, "Next chapter", enabled = chapterIndex + 1 < b.chapters.size) {
                                b.chapters.getOrNull(chapterIndex + 1)?.let { seekBook(app, id, it.startMs) }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(timeText(position - chapterStart), Modifier.width(52.dp), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .75f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            SeekBar(((position - chapterStart).toFloat() / (chapterEnd - chapterStart).coerceAtLeast(1)), { f ->
                                seekBook(app, id, chapterStart + (f * (chapterEnd - chapterStart)).toLong())
                            }, Modifier.weight(1f), color = Color.White)
                            Text("-" + timeText(chapterEnd - position), Modifier.width(52.dp), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .75f))
                        }
                    }
                    // Speed, sleep, bookmark, chapters, volume.
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        var speedMenu by remember { mutableStateOf(false) }
                        Box {
                            Text(speedLabel(state.speed), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(CircleShape).border(1.dp, Color.White.copy(alpha = .22f), CircleShape).clickable { speedMenu = true }
                                    .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp, vertical = 6.dp))
                            DropdownMenu(speedMenu, { speedMenu = false }) {
                                listOf(.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { s ->
                                    DropdownMenuItem({ Text(speedLabel(s) + if (s == state.speed) "  ✓" else "") }, { speedMenu = false; app.player.setSpeed(s) })
                                }
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        var sleepMenu by remember { mutableStateOf(false) }
                        val sleeping = state.sleepEndsAt != null || state.sleepAtChapterEnd
                        Box {
                            AppIconButton(if (sleeping) "ic_sleep_filled" else "ic_sleep",
                                state.sleepEndsAt?.let { "Sleep in ${((it - System.currentTimeMillis()) / 60_000 + 1)} min" } ?: if (state.sleepAtChapterEnd) "Sleep at chapter end" else "Sleep timer",
                                { sleepMenu = true }, iconSize = 20.dp, tint = if (sleeping) palette.accent else Color.White)
                            DropdownMenu(sleepMenu, { sleepMenu = false }) {
                                DropdownMenuItem({ Text("Off") }, { sleepMenu = false; app.player.sleepAfter(null) })
                                listOf(5, 10, 15, 30, 45, 60, 90).forEach { m -> DropdownMenuItem({ Text("$m minutes") }, { sleepMenu = false; app.player.sleepAfter(m) }) }
                                DropdownMenuItem({ Text("End of chapter") }, { sleepMenu = false; app.player.sleepAtChapterEnd() })
                            }
                        }
                        AppIconButton("ic_bookmark", "Bookmark", {
                            val name = (chapter?.title?.let { "$it · " } ?: "") + timeText(position)
                            app.library.update(id) { it.copy(bookmarks = (it.bookmarks + Bookmark(position, name, System.currentTimeMillis())).sortedBy { m -> m.positionMs }) }
                            app.messages.tryEmit("Bookmarked $name")
                        }, iconSize = 20.dp)
                        var contents by remember { mutableStateOf(false) }
                        AppIconButton("ic_chapters", "Chapters and bookmarks", { contents = true }, iconSize = 20.dp)
                        if (contents) ContentsDialog(app, b, position) { contents = false }
                        VolumeControl(app)
                    }
                }
            }
        }
    }
}

@Composable
private fun VectorButton(icon: ImageVector, description: String, size: Dp = 40.dp, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = Color.White.copy(alpha = if (enabled) .85f else .3f)
    Box(Modifier.size(size).clip(CircleShape).background(if (hovered && enabled) Color.White.copy(alpha = .10f) else Color.Transparent)
        .hoverable(interaction).clickable(interaction, null, enabled = enabled, onClick = onClick).pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(24.dp), tint = tint)
    }
}

@Composable
private fun VolumeControl(app: AppState) {
    val settings by app.settingsStore.settings.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(130.dp).padding(start = 6.dp)) {
        Icon(if (settings.volume < .01f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
            "Volume", Modifier.size(20.dp).clip(CircleShape).clickable { app.player.setVolume(if (settings.volume < .01f) 1f else 0f) }.pointerHoverIcon(PointerIcon.Hand),
            tint = Color.White.copy(alpha = .85f))
        Spacer(Modifier.width(6.dp))
        SeekBar(settings.volume, { app.player.setVolume(it) }, Modifier.weight(1f), color = Color.White.copy(alpha = .85f))
    }
}
