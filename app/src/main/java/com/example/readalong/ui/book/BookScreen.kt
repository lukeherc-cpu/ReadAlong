package com.example.readalong.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.readalong.R
import com.example.readalong.ui.components.BookCover
import com.example.readalong.ui.library.remainingTimeLabel

@Composable
fun BookScreen(
    state: BookUiState,
    busy: Boolean,
    updateFailed: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onFinishedChange: (Boolean) -> Unit,
) {
    Scaffold { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back_to_library), Modifier.size(24.dp))
                }
                Text(stringResource(R.string.book_details), style = MaterialTheme.typography.titleLarge)
            }
            when (state) {
                BookUiState.Loading -> CircularProgressIndicator(Modifier.padding(48.dp))
                BookUiState.Error -> Column(Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.book_load_error))
                    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
                }
                BookUiState.Missing -> Text(stringResource(R.string.book_missing), Modifier.padding(24.dp))
                is BookUiState.Ready -> {
                    val book = state.book
                    Column(
                        Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (book.isDemo) Text(stringResource(R.string.sample_book), color = MaterialTheme.colorScheme.primary)
                        BookCover(book, Modifier.fillMaxWidth(), large = true)
                        Spacer(Modifier.height(8.dp))
                        Text(book.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                        Text(book.author ?: stringResource(R.string.unknown_author), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { book.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.progress_complete, book.progressPercent), style = MaterialTheme.typography.bodyMedium)
                        Text(remainingTimeLabel(book), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        book.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        Text(stringResource(R.string.player_preview_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            onClick = { onFinishedChange(!book.isFinished) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(if (book.isFinished) R.string.mark_unfinished else R.string.mark_finished))
                        }
                        if (updateFailed) Text(stringResource(R.string.book_update_error), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
