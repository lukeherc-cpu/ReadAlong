package com.example.readalong.ui.launch

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.readalong.R

@Composable
fun LibraryLogo(scanningFolder: String?, transcribing: Boolean = false, animate: Boolean = true, onClick: () -> Unit = {}) {
    val moving = (scanningFolder != null || transcribing) && animate && ValueAnimator.areAnimatorsEnabled()
    val page = if (moving) {
        val transition = rememberInfiniteTransition(label = "Transcribing pages")
        val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = PageTurnEasing)), label = "Page turn")
        phase
    } else 0f
    Column(Modifier.widthIn(min = 64.dp).clickable(onClickLabel = "Open the queue", onClick = onClick).semantics(mergeDescendants = true) {
        contentDescription = "ReadAlong queue"
        stateDescription = if (transcribing) "Transcribing" else scanningFolder?.let { "Syncing $it" } ?: "Library up to date"
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        PageTurnLogo(page, Modifier.size(52.dp))
        // The label's line is always reserved, so the toolbar doesn't jump when work starts or stops.
        Box(Modifier.height(14.dp), contentAlignment = Alignment.Center) {
            // The page turns for either job; say which one.
            val label = if (transcribing) "Transcribing" else if (scanningFolder != null) "Syncing" else null
            if (label != null) Text(label, fontSize = 12.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun PageTurnLogo(progress: Float, modifier: Modifier, safeInset: Boolean = false) {
    val settings = com.example.readalong.ui.theme.LocalAppSettings.current
    val palette = com.example.readalong.ui.theme.basicPalette(settings.basicColor)
    val ink = if (settings.libraryLight) palette.preview else palette.accent
    val filter = remember(ink) {
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
        Image(painterResource(if (safeInset) R.drawable.ic_splash_logo else R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize(), colorFilter = filter)
        if (progress > 0f && progress < 1f) Canvas(Modifier.fillMaxSize()) {
            val u = size.width / if (safeInset) 144f else 108f
            val inset = if (safeInset) 18*u else 0f
            // The page turns about the spine (x = 54) and is drawn in 2D each frame: its outer edge
            // swings toward the viewer (taller as it nears edge-on), lags slightly so the sheet bends
            // like paper, and is shaded by its angle. It lands exactly on the left page, so there is
            // no pop or fade at the end.
            val angle = Math.PI.toFloat() * progress
            val cos = kotlin.math.cos(angle); val sin = kotlin.math.sin(angle)
            fun point(x: Float, y: Float): Offset {
                val reach = (x - 54f) / 19f                       // 0 at the spine, 1 at the outer edge
                val bend = -3.2f * sin * reach * reach            // the free edge trails the turn
                val lift = 1f + .14f * sin * reach                // nearer the viewer looks taller
                return Offset((54f + (x - 54f) * cos + bend) * u + inset, (58f + (y - 58f) * lift) * u + inset)
            }
            fun Path.cubic(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                val a = point(x1, y1); val b = point(x2, y2); val c = point(x3, y3); cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)
            }
            val start = point(55f, 49f); val corner = point(73f, 74f)
            val sheet = Path().apply {
                moveTo(start.x, start.y); cubic(59f, 44f, 65f, 41f, 73f, 42f)
                lineTo(corner.x, corner.y); cubic(67f, 73f, 61f, 75f, 55f, 80f); close()
            }
            // Only the turning sheet is drawn: the page underneath stays exactly as it is in the mark.
            val paper = Color(0xFFF7FAFD)
            val face = if (cos >= 0f) lerp(paper, Color(0xFFDDE4EC), .45f * sin) else lerp(paper, Color(0xFFE6ECF2), .35f * sin)
            drawPath(sheet, face)
            // The outline only defines the lifted sheet; it is gone when the sheet lies flat on either page.
            drawPath(sheet, ink.copy(alpha = (sin * 2.5f).coerceAtMost(1f)), style = androidx.compose.ui.graphics.drawscope.Stroke(u, join = StrokeJoin.Round))
        }
    }
}

/** Lifts gently, sweeps through the middle, settles softly: closer to a hand turning a page than FastOutSlowIn. */
internal val PageTurnEasing = CubicBezierEasing(.42f, 0f, .18f, 1f)
