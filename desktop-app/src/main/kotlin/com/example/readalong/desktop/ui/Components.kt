@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.readalong.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun timeText(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600; val m = total % 3600 / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
fun hoursMinutes(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) + 59_999) / 60_000
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

/** An icon button using the phone's icons, with a soft hover circle and a press dip. */
@Composable
fun AppIconButton(icon: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp,
    iconSize: Dp = 22.dp, tint: Color = LocalContentColor.current, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(if (hovered && enabled) tint.copy(alpha = .10f) else Color.Transparent, tween(120))
    val scale by animateFloatAsState(if (pressed) .9f else 1f, spring(dampingRatio = .6f, stiffness = 800f))
    TooltipArea(tooltip = { Tooltip(description) }, delayMillis = 600) {
        Box(modifier.size(size).scale(scale).clip(CircleShape).background(background)
            .hoverable(interaction).clickable(interaction, null, enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center) {
            Icon(AppIcons.get(icon), description, Modifier.size(iconSize), tint = if (enabled) tint else tint.copy(alpha = .38f))
        }
    }
}

@Composable
fun Tooltip(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
    }
}

/** A labelled control under the player (Speed, Sleep, Bookmark, Chapters), like the phone's. */
@Composable
fun LabeledAction(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current
    Column(modifier.clip(RoundedCornerShape(Corners.medium)).background(if (hovered) tint.copy(alpha = .08f) else Color.Transparent)
        .hoverable(interaction).clickable(interaction, null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(AppIcons.get(icon), label, Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

/**
 * The player's progress bar: a thin rounded track with a round thumb that grows on hover and while
 * dragging. Seeks once, when released, so dragging never stutters the audio.
 */
@Composable
fun SeekBar(fraction: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary,
    track: Color = color.copy(alpha = .25f), secondary: Float? = null) {
    var width by remember { mutableStateOf(1) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val thumb by animateDpAsState(if (dragging != null) 9.dp else if (hovered) 8.dp else 6.dp, spring(stiffness = 700f))
    val shown = dragging ?: fraction
    Canvas(modifier.height(24.dp).fillMaxWidth().onSizeChanged { width = it.width }.hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
        .pointerInput(Unit) {
            detectTapGestures { offset -> onSeek((offset.x / width).coerceIn(0f, 1f)) }
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { dragging = (it.x / width).coerceIn(0f, 1f) },
                onDragEnd = { dragging?.let(onSeek); dragging = null },
                onDragCancel = { dragging = null }) { change, _ -> dragging = (change.position.x / width).coerceIn(0f, 1f) }
        }) {
        val y = size.height / 2; val h = 4.dp.toPx(); val r = thumb.toPx()
        drawRoundRect(track, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
        secondary?.let { drawRoundRect(color.copy(alpha = .35f), Offset(0f, y - h / 2), Size(size.width * it.coerceIn(0f, 1f), h), CornerRadius(h / 2)) }
        drawRoundRect(color, Offset(0f, y - h / 2), Size(size.width * shown.coerceIn(0f, 1f), h), CornerRadius(h / 2))
        drawCircle(color, r, Offset(size.width * shown.coerceIn(0f, 1f), y))
    }
}

/** A rounded, animated progress line. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, height: Dp = 4.dp) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(stiffness = 120f))
    Canvas(modifier.height(height).fillMaxWidth()) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(color.copy(alpha = .22f), cornerRadius = r)
        if (animated > 0f) drawRoundRect(color, size = Size(size.width * animated, size.height), cornerRadius = r)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/** A pill-shaped button: filled for the main action, tonal otherwise. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: String? = null, filled: Boolean = true, enabled: Boolean = true) {
    val colors = if (filled) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
    Button(onClick, modifier.pointerHoverIcon(PointerIcon.Hand), enabled = enabled, colors = colors, shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
        icon?.let { Icon(AppIcons.get(it), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The full player's volume: speaker, slider, louder speaker. Unlike [SeekBar] it follows the drag live, shows the
 * level while hovered or dragged, and the mouse wheel over it moves it in 5% steps. The low speaker mutes and unmutes.
 */
@Composable
fun VolumeSlider(app: AppState, modifier: Modifier = Modifier) {
    val volume = app.settingsStore.settings.collectAsState().value.volume
    val muted = volume < .01f
    val tint = Color.White.copy(alpha = .85f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(if (muted) androidx.compose.material.icons.Icons.Rounded.VolumeOff else androidx.compose.material.icons.Icons.Rounded.VolumeDown,
            if (muted) "Unmute (M)" else "Mute (M)",
            Modifier.size(32.dp).clip(CircleShape).clickable { app.player.toggleMute() }.pointerHoverIcon(PointerIcon.Hand).padding(6.dp),
            tint = if (muted) MaterialTheme.colorScheme.primary else tint)
        Spacer(Modifier.width(8.dp))
        var width by remember { mutableStateOf(1) }
        var dragging by remember { mutableStateOf(false) }
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val thumb by animateDpAsState(if (dragging) 9.dp else if (hovered) 8.dp else 6.dp, spring(stiffness = 700f))
        Box(Modifier.weight(1f)) {
            Canvas(Modifier.height(24.dp).fillMaxWidth().onSizeChanged { width = it.width }.hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(Unit) { detectTapGestures { app.player.setVolume((it.x / width).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; app.player.setVolume((it.x / width).coerceIn(0f, 1f)) },
                        onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { change, _ ->
                        app.player.setVolume((change.position.x / width).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            // The Initial pass, after the scrolling column around the player has claimed the wheel, so it stays still.
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (event.type != androidx.compose.ui.input.pointer.PointerEventType.Scroll) continue
                            val dy = event.changes.fold(0f) { sum, c -> sum + c.scrollDelta.y }
                            if (dy == 0f) continue
                            claimWheelForControl()
                            event.changes.forEach { it.consume() }
                            app.player.nudgeVolume(if (dy < 0) .05f else -.05f)
                        }
                    }
                }) {
                val y = size.height / 2; val h = 4.dp.toPx(); val r = thumb.toPx()
                drawRoundRect(Color.White.copy(alpha = .22f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(tint, Offset(0f, y - h / 2), Size(size.width * volume, h), CornerRadius(h / 2))
                drawCircle(Color.White, r, Offset(size.width * volume, y))
            }
            if (hovered || dragging) {
                Surface(Modifier.align(Alignment.TopStart).offset { androidx.compose.ui.unit.IntOffset((width * volume).toInt(), -26.dp.roundToPx()) }
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        layout(p.width, p.height) { p.place(-p.width / 2, 0) }
                    }, shape = RoundedCornerShape(6.dp), color = Color.White, contentColor = Color(0xFF111111)) {
                    Text("${Math.round(volume * 100)}%", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(androidx.compose.material.icons.Icons.Rounded.VolumeUp, null, Modifier.size(20.dp), tint = tint)
    }
}
