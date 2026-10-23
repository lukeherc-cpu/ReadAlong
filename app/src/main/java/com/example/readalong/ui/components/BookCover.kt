package com.example.readalong.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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

/** Quiet title-only placeholder if local artwork is absent or unavailable. */
@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier, large: Boolean = false, showTitle: Boolean = true) {
    val light = com.example.readalong.ui.theme.LocalAppSettings.current.libraryLight
    val ink = if (light) Color(0xFF272727) else Color(0xFFE8E8E8)
    val localCover = remember(book.coverUri) {
        book.coverUri?.takeIf { it.startsWith("content://") || it.startsWith("android.resource://") }
    }
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp))
            .background(if (light) Color(0xFFF2F0E9) else Color(0xFF303030)).clearAndSetSemantics { },
    ) {
        if (showTitle) Column(Modifier.fillMaxSize().padding(if (large) 28.dp else 16.dp)) {
            Text(
                book.title,
                color = ink,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (large) 36.sp else 22.sp,
                lineHeight = if (large) 39.sp else 25.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
        }
        if (localCover != null) {
            AsyncImage(
                model = localCover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
