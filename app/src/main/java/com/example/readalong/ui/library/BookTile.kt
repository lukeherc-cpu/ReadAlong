package com.example.readalong.ui.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
            if (showMetadata) Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                shape = RoundedCornerShape(8.dp),
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
        }
        if (showMetadata) {
        Spacer(Modifier.height(10.dp))
        Text(book.title, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        Text(
            book.author ?: stringResource(R.string.unknown_author),
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
            Text((book.author ?: stringResource(R.string.unknown_author)).uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(book.title, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(if (book.isFinished) "Finished" else "${book.progressPercent}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
