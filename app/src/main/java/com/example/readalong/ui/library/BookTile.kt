package com.example.readalong.ui.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.readalong.R
import com.example.readalong.domain.model.Book
import com.example.readalong.ui.components.BookCover
import com.example.readalong.ui.components.navSharedCover

@Composable
fun BookTile(book: Book, selected: Boolean = false, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    val showMetadata = com.example.readalong.ui.theme.LocalAppSettings.current.showTitleAndAuthor
    val openLabel = stringResource(R.string.open_book, book.title)
    Column(
        Modifier.fillMaxWidth().testTag("book:${book.id}").semantics { if (!showMetadata) contentDescription = openLabel }
            .combinedClickable(role = Role.Button, onClickLabel = openLabel, onLongClickLabel = "Edit book", onLongClick = onLongClick, onClick = onClick),
    ) {
        Box {
            BookCover(book, Modifier.fillMaxWidth().navSharedCover(com.example.readalong.ui.components.coverKey(book.id)))
            // The percentage badge by default; a thin progress line when "Progress bar on covers" is on.
            val bar = com.example.readalong.ui.theme.LocalAppSettings.current.coverProgressBar
            if (showMetadata && !bar) Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.small),
                color = Color(0xE6121617),
                contentColor = Color.White,
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ring = com.example.readalong.ui.components.animatedFraction(book.progressPercent / 100f, "Listening progress")
                    CircularProgressIndicator(
                        progress = { ring.value },
                        modifier = Modifier.size(14.dp).clearAndSetSemantics { },
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = .3f),
                        strokeWidth = 2.dp,
                    )
                    Text(stringResource(R.string.progress_percent, book.progressPercent), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (bar && book.progressPercent in 1..99) {
                val ring = com.example.readalong.ui.components.animatedFraction(book.progressPercent / 100f, "Listening progress")
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Color.Black.copy(alpha = .35f)).clearAndSetSemantics { }) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(ring.value).background(Color.White.copy(alpha = .92f)))
                }
            }
        }
        if (showMetadata) {
        Spacer(Modifier.height(10.dp))
        Text(book.title, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        Text(
            (book.author ?: stringResource(R.string.unknown_author)) + if (com.example.readalong.ui.theme.LocalAppSettings.current.coverProgressBar) when { book.isFinished -> " · Finished"; book.progressPercent > 0 -> " · ${book.progressPercent}%"; else -> "" } else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        }
    }
}

@Composable
fun remainingTimeLabel(book: Book): String {
    if (book.isFinished) return stringResource(R.string.section_finished)
    val remaining = book.remainingMs ?: return stringResource(R.string.duration_unknown)
    val minutes = (remaining / 60_000).coerceAtLeast(if (remaining > 0) 1 else 0)
    return stringResource(R.string.time_remaining, minutes / 60, minutes % 60)
}

@Composable
fun BookListRow(book: Book, selected: Boolean = false, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("book:${book.id}").combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BookCover(book, Modifier.size(76.dp), showTitle = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(book.author ?: stringResource(R.string.unknown_author), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(book.title, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            // Listening progress: a thin bar with the percentage beside it.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(progress = { if (book.isFinished) 1f else book.progressPercent / 100f },
                    modifier = Modifier.weight(1f).height(4.dp), color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    gapSize = 0.dp, drawStopIndicator = {})
                // Fixed width, so every row's bar ends in the same place.
                Text(if (book.isFinished) "Finished" else "${book.progressPercent}%", Modifier.widthIn(min = 58.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Start, maxLines = 1)
            }
        }
    }
}
