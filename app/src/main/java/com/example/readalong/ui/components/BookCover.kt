package com.example.readalong.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.readalong.domain.model.Book

/**
 * The book's artwork, or a generated cover in the book's own colours: a soft gradient, the title in
 * a serif and the author underneath (just the initial when the cover is small).
 */
@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier, large: Boolean = false, showTitle: Boolean = true) {
    val localCover = remember(book.coverUri) {
        book.coverUri?.takeIf { it.startsWith("content://") || it.startsWith("android.resource://") }
    }
    val palette = remember(book.title, book.author) { placeholderPalette(book) }
    val ink = palette.accent
    BoxWithConstraints(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(if (large) 20.dp else 12.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(palette.preview, palette.background))).clearAndSetSemantics { },
    ) {
        val small = maxWidth < 110.dp
        if (localCover == null) {
            if (small || !showTitle) Text(book.title.trim().take(1).uppercase(), Modifier.align(Alignment.Center), color = ink,
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = (maxWidth.value * .42f).sp)
            else {
                // Longer titles step down a size instead of wrapping into an ellipsis.
                val long = book.title.length > 26
                val size = (if (large) 34f else 21f) * (if (long) .8f else 1f)
                Column(Modifier.fillMaxSize().padding(if (large) 26.dp else 14.dp)) {
                    Box(Modifier.width(if (large) 36.dp else 22.dp).height(2.dp).background(ink.copy(alpha = .6f)))
                    Spacer(Modifier.height(if (large) 14.dp else 9.dp))
                    Text(book.title, color = ink, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = (size * 1.12f).sp,
                        maxLines = 4, overflow = TextOverflow.Ellipsis)
                    // The author sits under the title, leaving the bottom corner free for the progress badge.
                    book.author?.let { Text(it, Modifier.padding(top = if (large) 10.dp else 6.dp), color = ink.copy(alpha = .75f), fontSize = if (large) 15.sp else 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = .4.sp) }
                }
            }
        } else AsyncImage(model = localCover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
