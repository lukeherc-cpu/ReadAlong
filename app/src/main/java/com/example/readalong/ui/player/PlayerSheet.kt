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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.readalong.domain.model.Book
import com.example.readalong.ui.components.BookCover

/** Where a dragged mini player was let go (0..1 of the screen), so the real player continues from there. */
object PlayerSheetHandoff { @Volatile var progress = 0f }

/** A light stand-in for the player while it is dragged up: same background, artwork position and title. */
@Composable
fun PlayerSheetShell(book: Book, modifier: Modifier = Modifier) {
    val palette = com.example.readalong.ui.components.rememberCoverPalette(book)
    val settings = com.example.readalong.ui.theme.LocalAppSettings.current
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    Box(modifier.fillMaxSize().background(palette.background)) {
        if (settings.blurredPlayer && !settings.alwaysBasicColor) com.example.readalong.ui.components.BlurredCover(book.coverUri, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(horizontal = 24.dp).widthIn(max = (screenHeight * 0.40f).coerceIn(260.dp, 380.dp)).fillMaxWidth().aspectRatio(1f)) {
                if (settings.openingView == "COVER") BookCover(book, Modifier.fillMaxSize(), large = true)
                else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(palette.preview))
            }
            Text(book.title, Modifier.padding(horizontal = 28.dp, vertical = 16.dp), color = Color.White, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
