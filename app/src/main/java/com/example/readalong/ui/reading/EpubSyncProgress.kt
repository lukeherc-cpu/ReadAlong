package com.example.readalong.ui.reading

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.data.local.ImportedEpubEntity

internal fun ImportedEpubEntity?.isInitialSyncPending(): Boolean = this != null && alignedRevision < 0 && state in setOf("QUEUED", "ALIGNING")

@Composable
fun EpubSyncProgress(model: ReadingViewModel, modifier: Modifier = Modifier) {
    val edition by model.importedEdition.collectAsStateWithLifecycle()
    if (edition.isInitialSyncPending()) Surface(modifier, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Syncing EPUB…", style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
