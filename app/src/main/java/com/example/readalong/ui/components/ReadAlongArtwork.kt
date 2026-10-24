package com.example.readalong.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.readalong.R

/**
 * Flat, two-tone line illustrations drawn in the current theme's colours (so they follow the accent
 * or the book's cover), replacing the old glossy PNGs. [resource] names which picture, by the old
 * art_* drawable id so call sites stay the same.
 */
@Composable
fun ReadAlongArtwork(resource: Int = R.drawable.art_readalong, modifier: Modifier = Modifier.fillMaxWidth().height(112.dp)) {
    val ink = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primaryContainer
    val paper = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        val side = minOf(size.width, size.height)
        // Work in a 100-unit square centred in the available space.
        translate((size.width - side) / 2, (size.height - side) / 2) {
            val u = side / 100f
            val line = Stroke(3.2f * u, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            fun p(x: Float, y: Float) = Offset(x * u, y * u)
            fun path(block: Path.() -> Unit) = Path().apply(block)
            fun Path.m(x: Float, y: Float) = moveTo(x * u, y * u)
            fun Path.l(x: Float, y: Float) = lineTo(x * u, y * u)
            fun Path.q(cx: Float, cy: Float, x: Float, y: Float) = quadraticTo(cx * u, cy * u, x * u, y * u)
            fun bar(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 3.2f, c: Color = ink) = drawLine(c, p(x1, y1), p(x2, y2), w * u, StrokeCap.Round)
            fun book(cx: Float, cy: Float, scale: Float, highlight: Boolean) {
                val left = path { m(cx, cy - 18 * scale); q(cx - 18 * scale, cy - 26 * scale, cx - 36 * scale, cy - 20 * scale); l(cx - 36 * scale, cy + 22 * scale)
                    q(cx - 18 * scale, cy + 16 * scale, cx, cy + 24 * scale); close() }
                val right = path { m(cx, cy - 18 * scale); q(cx + 18 * scale, cy - 26 * scale, cx + 36 * scale, cy - 20 * scale); l(cx + 36 * scale, cy + 22 * scale)
                    q(cx + 18 * scale, cy + 16 * scale, cx, cy + 24 * scale); close() }
                drawPath(left, paper); drawPath(right, paper); drawPath(left, ink, style = line); drawPath(right, ink, style = line)
                for (i in 0..3) {
                    val y = cy - 9 * scale + i * 8 * scale
                    bar(cx - 28 * scale, y, cx - 8 * scale, y + 1.5f * scale, 2.4f, ink.copy(alpha = .45f))
                    if (highlight && i == 1) { bar(cx + 8 * scale, y, cx + 28 * scale, y - 1.5f * scale, 6f, fill); bar(cx + 8 * scale, y, cx + 28 * scale, y - 1.5f * scale, 2.4f) }
                    else bar(cx + 8 * scale, y, cx + 28 * scale, y - 1.5f * scale, 2.4f, ink.copy(alpha = .45f))
                }
            }
            fun document(x: Float, y: Float, w: Float, h: Float) {
                val fold = w * .28f
                val sheet = path { m(x, y); l(x + w - fold, y); l(x + w, y + fold); l(x + w, y + h); l(x, y + h); close() }
                drawPath(sheet, fill); drawPath(sheet, ink, style = line)
                drawPath(path { m(x + w - fold, y); l(x + w - fold, y + fold); l(x + w, y + fold) }, ink, style = line)
            }
            // A soft disc behind every picture ties them together.
            drawCircle(fill.copy(alpha = .55f), 44 * u, p(50f, 50f))
            when (resource) {
                R.drawable.art_book -> book(50f, 52f, 1f, highlight = true)
                R.drawable.art_epub -> {
                    book(30f, 52f, .62f, highlight = true)
                    // Link
                    drawRoundRect(ink, p(46f, 47f), androidx.compose.ui.geometry.Size(10 * u, 6 * u), androidx.compose.ui.geometry.CornerRadius(3 * u), style = Stroke(2.6f * u))
                    drawRoundRect(ink, p(52f, 51f), androidx.compose.ui.geometry.Size(10 * u, 6 * u), androidx.compose.ui.geometry.CornerRadius(3 * u), style = Stroke(2.6f * u))
                    document(66f, 30f, 24f, 34f)
                    for (i in 0..2) bar(71f, 46f + i * 6, 85f, 46f + i * 6, 2.4f)
                }
                R.drawable.art_audio_file -> {
                    document(32f, 22f, 36f, 52f)
                    listOf(6f, 12f, 18f, 10f, 14f, 6f).forEachIndexed { i, h -> val x = 40f + i * 4f; bar(x, 52f - h / 2, x, 52f + h / 2, 2.6f) }
                }
                R.drawable.art_folder -> {
                    val back = path { m(22f, 34f); l(42f, 34f); l(47f, 40f); l(78f, 40f); l(78f, 72f); l(22f, 72f); close() }
                    drawPath(back, fill); drawPath(back, ink, style = line)
                    // A book peeking out of the folder
                    drawRoundRect(paper, p(34f, 28f), androidx.compose.ui.geometry.Size(14 * u, 20 * u), androidx.compose.ui.geometry.CornerRadius(2 * u))
                    drawRoundRect(ink, p(34f, 28f), androidx.compose.ui.geometry.Size(14 * u, 20 * u), androidx.compose.ui.geometry.CornerRadius(2 * u), style = line)
                    val front = path { m(22f, 48f); l(78f, 48f); l(78f, 72f); l(22f, 72f); close() }
                    drawPath(front, fill); drawPath(front, ink, style = line)
                }
                R.drawable.art_battery -> {
                    drawRoundRect(paper, p(26f, 36f), androidx.compose.ui.geometry.Size(44 * u, 28 * u), androidx.compose.ui.geometry.CornerRadius(6 * u))
                    drawRoundRect(ink, p(26f, 36f), androidx.compose.ui.geometry.Size(44 * u, 28 * u), androidx.compose.ui.geometry.CornerRadius(6 * u), style = line)
                    bar(74f, 45f, 74f, 55f, 4f)
                    drawRoundRect(fill, p(31f, 41f), androidx.compose.ui.geometry.Size(20 * u, 18 * u), androidx.compose.ui.geometry.CornerRadius(3 * u))
                    val bolt = path { m(50f, 39f); l(42f, 51f); l(49f, 51f); l(46f, 61f); l(55f, 48f); l(48f, 48f); close() }
                    drawPath(bolt, ink)
                }
                else -> {
                    // ReadAlong: an open book under headphones.
                    drawArc(ink, 180f, 180f, false, p(26f, 18f), androidx.compose.ui.geometry.Size(48 * u, 48 * u), style = line)
                    drawRoundRect(ink, p(20f, 40f), androidx.compose.ui.geometry.Size(9 * u, 16 * u), androidx.compose.ui.geometry.CornerRadius(4 * u))
                    drawRoundRect(ink, p(71f, 40f), androidx.compose.ui.geometry.Size(9 * u, 16 * u), androidx.compose.ui.geometry.CornerRadius(4 * u))
                    book(50f, 60f, .72f, highlight = true)
                }
            }
        }
    }
}

@Composable
fun BackgroundWaveform() {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val heights = listOf(.2f,.5f,.9f,.6f,1f,.65f,.35f,.18f)
        val width = 96.dp.toPx()
        heights.forEachIndexed { i, h ->
            val x = (size.width - width)/2 + (i + .5f)*width/heights.size
            drawLine(ink, Offset(x, size.height*(1-h)/2), Offset(x,size.height*(1+h)/2), 5.dp.toPx(), StrokeCap.Round)
        }
    }
}
