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

/**
 * "34 / 120 remaining", the one count format every progress view uses. "remaining" because the total is only
 * the books still to be added or updated, not the whole library.
 */
fun ScanProgress.count() = "$done / $total remaining"

private fun ScanProgress.fraction() = if (total == 0) 0f else done.toFloat() / total

/** The first read of a newly added library folder: Reading “Audiobooks”, the count, and a bar under them. */
@Composable
fun ReadingFolderLine(progress: ScanProgress, modifier: Modifier = Modifier) {
    val fraction by animatedFraction(progress.fraction(), "Folder reading progress")
    val figures = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {
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
        FolderProgressBar(fraction)
    }
}

/**
 * The folder-reading bar the library and the Library folders page share: 4dp, rounded, primary on a 15%
 * primary track. [fraction] null (books not counted yet) slides the same bar instead of filling it.
 */
@Composable
fun FolderProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.primary.copy(alpha = .15f)
    val bar = modifier.fillMaxWidth().height(4.dp)
    if (fraction == null) LinearProgressIndicator(bar, color = color, trackColor = track, strokeCap = StrokeCap.Round, gapSize = 0.dp)
    else LinearProgressIndicator(progress = { fraction }, modifier = bar, color = color, trackColor = track,
        strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
}

/** The animated fraction of [progress] done, or null while there is none, for [FolderProgressBar]. */
@Composable
fun animatedScanFraction(progress: ScanProgress?): Float? {
    val fraction by animatedFraction(progress?.fraction() ?: 0f, "Folder reading progress")
    return if (progress == null) null else fraction
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
