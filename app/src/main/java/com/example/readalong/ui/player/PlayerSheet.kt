package com.example.readalong.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.readalong.domain.model.Book
import com.example.readalong.ui.components.BookCover

/**
 * The mini player ↔ player transform. The library draws it: a container that grows from the mini
 * player's own bounds to the whole screen (and back), its colour moving from the mini player's to
 * the player's, the mini player's row fading out and a stand-in of the player fading in. Everything
 * is laid out once; per frame only the clip, colour and alphas change (draw phase), so it stays
 * smooth. At full size the real player fades in on top of it.
 */
object PlayerSheetHandoff {
    /** Mini player bounds in root coordinates, kept from its last layout. */
    @Volatile var miniBounds: Rect? = null
    /** Set when a player opened from the mini player closes, so the library plays the transform in reverse. */
    @Volatile var closing: String? = null
}

/** Mini player colours, shared by the mini player and the transform so they match exactly. */
@Composable
fun miniPlayerColors(book: Book, readerTheme: String): Pair<Color, Color> {
    val palette = com.example.readalong.ui.components.rememberCoverPalette(book)
    val settings = com.example.readalong.ui.theme.LocalAppSettings.current
    val basic = com.example.readalong.ui.theme.basicPalette(settings.basicColor)
    if (!settings.coloredMiniPlayer) return (if (settings.libraryLight) basic.accent else basic.background) to (if (settings.libraryLight) basic.background else Color.White)
    // Tinted from the book; on the light library a light tint instead of a heavy dark bar.
    val background = when (readerTheme) {
        "LIGHT" -> Color(0xFFFAF9F5); "SEPIA" -> Color(0xFFF0E6D2); "BLACK" -> Color.Black
        else -> if (settings.libraryLight) lerp(palette.accent, Color.White, .2f) else palette.background
    }
    return background to (if (readerTheme == "LIGHT" || readerTheme == "SEPIA") Color(0xFF242820) else if (readerTheme == "BLACK") Color.White else if (settings.libraryLight) palette.background else Color.White)
}

/** Records where the mini player is, for the transform to start from. */
fun Modifier.miniPlayerBounds(): Modifier = onGloballyPositioned { coordinates ->
    val p = coordinates.positionInRoot()
    PlayerSheetHandoff.miniBounds = Rect(p.x, p.y, p.x + coordinates.size.width, p.y + coordinates.size.height)
}

private class TransformShape(private val progress: () -> Float, private val from: () -> Rect?, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = progress().coerceIn(0f, 1f)
        val start = from() ?: Rect(0f, size.height * .85f, size.width, size.height)
        val r = radius * (1f - p)
        return Outline.Rounded(RoundRect(
            androidx.compose.ui.util.lerp(start.left, 0f, p), androidx.compose.ui.util.lerp(start.top, 0f, p),
            androidx.compose.ui.util.lerp(start.right, size.width, p), androidx.compose.ui.util.lerp(start.bottom, size.height, p),
            CornerRadius(r, r)))
    }
}

@Composable
fun PlayerTransform(book: Book, readerTheme: String, progress: () -> Float, modifier: Modifier = Modifier, chapterTitle: String = "") {
    val palette = com.example.readalong.ui.components.rememberCoverPalette(book)
    val settings = com.example.readalong.ui.theme.LocalAppSettings.current
    val (miniBackground, miniForeground) = miniPlayerColors(book, readerTheme)
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val density = LocalDensity.current
    val shape = androidx.compose.runtime.remember(density) { TransformShape(progress, { PlayerSheetHandoff.miniBounds }, with(density) { 18.dp.toPx() }) }
    Box(modifier.fillMaxSize().graphicsLayer { this.shape = shape; clip = true }
        .drawBehind { drawRect(lerp(miniBackground, palette.background, (progress() * 1.6f).coerceIn(0f, 1f))) }) {
        // The player, laid out once at full size, rides the top of the growing container.
        Box(Modifier.fillMaxSize().graphicsLayer {
            val p = progress().coerceIn(0f, 1f)
            val start = PlayerSheetHandoff.miniBounds
            if (start != null) { translationY = start.top * (1f - p); translationX = start.left * (1f - p) }
            alpha = ((p - .15f) / .45f).coerceIn(0f, 1f)
        }) {
            if (settings.blurredPlayer && !settings.alwaysBasicColor) com.example.readalong.ui.components.BlurredCover(book.coverUri, Modifier.fillMaxSize())
            // Same measurements as PlayerScreen (top bar 56 dp, 24 dp margins, 12 dp gaps, left-aligned
            // title and chapter), so nothing moves when the real player takes over.
            val playerColors = com.example.readalong.ui.theme.playerScheme(palette)
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = (screenHeight * 0.40f).coerceIn(260.dp, 380.dp)).fillMaxWidth().aspectRatio(1f)) {
                        if (settings.openingView == "COVER") BookCover(book, Modifier.fillMaxSize(), large = true)
                        else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(com.example.readalong.ui.theme.Corners.large)).background(palette.preview))
                    }
                }
                Text(book.title, Modifier.fillMaxWidth(), color = playerColors.onSurface, style = MaterialTheme.typography.headlineMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                if (chapterTitle.isNotBlank()) Text(chapterTitle, color = playerColors.onSurfaceVariant, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        }
        // The mini player's row rides the top of the container and fades as it grows.
        val bounds = PlayerSheetHandoff.miniBounds
        if (bounds != null) with(density) {
            Row(Modifier.width(bounds.width.toDp()).height(bounds.height.toDp()).graphicsLayer {
                    val p = progress().coerceIn(0f, 1f)
                    translationX = bounds.left * (1f - p); translationY = bounds.top * (1f - p)
                    alpha = (1f - p / .3f).coerceIn(0f, 1f)
                }.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BookCover(book, Modifier.size(52.dp), showTitle = false)
                Text(book.title, Modifier.weight(1f), color = miniForeground, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
