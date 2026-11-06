package com.example.readalong.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.readalong.data.importing.ScanProgress
import com.example.readalong.ui.components.animatedFraction

/** "34 / 120", the one count format both progress views use. */
internal fun ScanProgress.count() = "$done / $total"

private fun ScanProgress.fraction() = if (total == 0) 0f else done.toFloat() / total

/** The first read of a newly added library folder: Reading “Audiobooks”, the count, and a bar under them. */
@Composable
fun ReadingFolderLine(progress: ScanProgress, modifier: Modifier = Modifier) {
    val fraction by animatedFraction(progress.fraction(), "Folder reading progress")
    val figures = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
    Column(modifier.fillMaxWidth().padding(bottom = 16.dp).semantics(mergeDescendants = true) {
        progressBarRangeInfo = ProgressBarRangeInfo(progress.fraction(), 0f..1f)
        stateDescription = "Reading ${progress.folder}, ${progress.done} of ${progress.total}"
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reading “${progress.folder}”", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(12.dp))
            Text(progress.count(), style = figures, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(4.dp),
            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = .15f),
            strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
    }
}

/** A background rescan that is taking a while: an edge-to-edge 2dp bar under the library header. */
@Composable
fun SyncBar(progress: ScanProgress, modifier: Modifier = Modifier) {
    val fraction by animatedFraction(progress.fraction(), "Sync progress")
    LinearProgressIndicator(progress = { fraction }, modifier = modifier.fillMaxWidth().height(2.dp).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(progress.fraction(), 0f..1f)
        stateDescription = "Syncing ${progress.folder}, ${progress.done} of ${progress.total}"
    }, color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = .15f),
        strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {})
}
