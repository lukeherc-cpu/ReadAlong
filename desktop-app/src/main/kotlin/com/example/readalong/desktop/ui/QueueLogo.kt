@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.readalong.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The phone's library logo, which is also the queue button: the book in headphones turns its pages while a
 * folder is read or a book is transcribed, with a short label underneath. "Syncing" shows for the first
 * 3 s of a library rescan; "Reading folder" for as long as a newly added folder is read.
 */
@Composable
fun QueueLogo(scanning: Boolean, readingFolder: Boolean, transcribing: Boolean, queued: Int, onClick: () -> Unit, size: Dp = 44.dp) {
    val moving = scanning || transcribing || readingFolder
    val page = if (moving) {
        val transition = rememberInfiniteTransition()
        val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = PageTurnEasing)))
        phase
    } else 0f
    var syncNote by remember { mutableStateOf(false) }
    LaunchedEffect(scanning) {
        syncNote = scanning
        if (syncNote) { delay(3_000); syncNote = false }
    }
    val label = if (readingFolder) "Reading folder" else if (transcribing) "Transcribing" else if (syncNote) "Syncing" else null
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(if (hovered) MaterialTheme.colorScheme.primary.copy(alpha = .10f) else Color.Transparent, tween(120))
    val scale by animateFloatAsState(if (pressed) .92f else 1f, spring(dampingRatio = .6f, stiffness = 800f))
    TooltipArea(tooltip = { Tooltip(if (queued > 0) "ReadAlong queue · $queued waiting" else "ReadAlong queue") }, delayMillis = 600) {
        Column(Modifier.widthIn(min = 64.dp).scale(scale).clip(RoundedCornerShape(Corners.medium)).background(background)
            .hoverable(interaction).clickable(interaction, null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            BadgedBox(badge = { if (queued > 0) Badge { Text("$queued") } }) { PageTurnLogo(page, Modifier.size(size)) }
            // The label's line is always reserved, so the header doesn't jump when work starts or stops.
            Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
                if (label != null) Text(label, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The launcher mark recoloured to the accent, with one sheet turning about the spine while [progress] runs 0 → 1. */
@Composable
fun PageTurnLogo(progress: Float, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    val filter = remember(ink) {
        // Maps the mark's blue-grey (red channel 73) to the accent and keeps its paper white (247).
        val paper = floatArrayOf(247f, 250f, 253f)
        val target = floatArrayOf(ink.red * 255, ink.green * 255, ink.blue * 255)
        val matrix = ColorMatrix()
        for (row in 0..2) {
            for (col in 0..4) matrix[row, col] = 0f
            val scale = (paper[row] - target[row]) / (247f - 73f)
            matrix[row, 0] = scale
            matrix[row, 4] = target[row] - scale * 73f
        }
        ColorFilter.colorMatrix(matrix)
    }
    Box(modifier) {
        Image(rememberVectorPainter(AppIcons.get("ic_launcher_foreground")), null, Modifier.fillMaxSize(), colorFilter = filter)
        if (progress > 0f && progress < 1f) Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 108f
            // The page turns about the spine (x = 54): its outer edge swings toward the viewer, lags so the
            // sheet bends like paper, and is shaded by its angle. It lands exactly on the left page.
            val angle = Math.PI.toFloat() * progress
            val cos = kotlin.math.cos(angle); val sin = kotlin.math.sin(angle)
            fun point(x: Float, y: Float): Offset {
                val reach = (x - 54f) / 19f
                val bend = -3.2f * sin * reach * reach
                val lift = 1f + .14f * sin * reach
                return Offset((54f + (x - 54f) * cos + bend) * u, (58f + (y - 58f) * lift) * u)
            }
            fun Path.cubic(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                val a = point(x1, y1); val b = point(x2, y2); val c = point(x3, y3); cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)
            }
            val start = point(55f, 49f); val corner = point(73f, 74f)
            val sheet = Path().apply {
                moveTo(start.x, start.y); cubic(59f, 44f, 65f, 41f, 73f, 42f)
                lineTo(corner.x, corner.y); cubic(67f, 73f, 61f, 75f, 55f, 80f); close()
            }
            val paper = Color(0xFFF7FAFD)
            val face = if (cos >= 0f) lerp(paper, Color(0xFFDDE4EC), .45f * sin) else lerp(paper, Color(0xFFE6ECF2), .35f * sin)
            drawPath(sheet, face)
            drawPath(sheet, ink.copy(alpha = (sin * 2.5f).coerceAtMost(1f)), style = Stroke(u, join = StrokeJoin.Round))
        }
    }
}

/** Lifts gently, sweeps through the middle, settles softly: closer to a hand turning a page than FastOutSlowIn. */
val PageTurnEasing = CubicBezierEasing(.42f, 0f, .18f, 1f)
