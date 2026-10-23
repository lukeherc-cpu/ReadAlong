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
        val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing)), label = "Page turn")
        phase
    } else 0f
    Column(Modifier.widthIn(min = 64.dp).clickable(onClickLabel = "Open transcription queue", onClick = onClick).semantics(mergeDescendants = true) {
        contentDescription = "Transcription queue"
        stateDescription = if (transcribing) "Transcribing" else scanningFolder?.let { "Scanning $it" } ?: "Library up to date"
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        PageTurnLogo(page, Modifier.size(52.dp))
        if (transcribing) Text("Transcribing", fontSize = 8.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.primary)
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
        if (progress > 0f && progress < 1f) Canvas(Modifier.fillMaxSize().graphicsLayer {
            rotationY = -180f * progress
            transformOrigin = TransformOrigin(.5f, .5f)
            cameraDistance = 16f * density
            alpha = if (progress > .85f) (1f - progress) / .15f else 1f
        }) {
            val u = size.width / if (safeInset) 144f else 108f
            val inset = if (safeInset) 18*u else 0f
            fun px(value: Float) = value * u + inset
            val sheet = Path().apply {
                moveTo(px(55f),px(49f)); cubicTo(px(59f),px(44f),px(65f),px(41f),px(73f),px(42f))
                lineTo(px(73f),px(74f)); cubicTo(px(67f),px(73f),px(61f),px(75f),px(55f),px(80f)); close()
            }
            drawPath(sheet, Color(0xFFF7FAFD))
            drawPath(sheet, palette.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(u))
        }
    }
}
