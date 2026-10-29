@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.readalong.desktop.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
            detectTapGesturesCompat { offset -> onSeek((offset.x / width).coerceIn(0f, 1f)) }
        }
        .pointerInput(Unit) {
            androidx.compose.foundation.gestures.detectHorizontalDragGestures(
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

suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGesturesCompat(onTap: (Offset) -> Unit) =
    androidx.compose.foundation.gestures.detectTapGestures(onTap = onTap)

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
