package com.example.readalong.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.readalong.R
import com.example.readalong.domain.i18n.tr
import com.example.readalong.playback.PlaybackControls
import com.example.readalong.playback.PlaybackState

private val SleepTimes = listOf(5, 10, 15, 30, 45, 60)

/**
 * The sleep timer sheet. Not set: the times as big tiles and End of chapter as a card. Running: a ring
 * counting down, +5 and +15 to add time, Restart, a reminder that a shake restarts it, and other times.
 */
@Composable
internal fun SleepTimerSheet(state: PlaybackState, playback: PlaybackControls, close: () -> Unit) {
    val now by produceState(System.currentTimeMillis(), state.sleepEndsAt) {
        while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000) }
    }
    val left = state.sleepEndsAt?.let { (it - now).coerceAtLeast(0) }
    val chapterLeft = ((state.chapterDurationMs - state.chapterPositionMs) / state.speed.coerceAtLeast(.1f)).toLong().coerceAtLeast(0)
    val running = left != null || state.sleepAtChapterEnd
    com.example.readalong.ui.components.ActionSheet(close, header = {
        // The title row, with Turn off beside it while a timer runs.
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Sleep timer"), Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            if (running) TextButton({ playback.sleep(null) }, Modifier.testTag("sleep_off")) { Text(tr("Turn off"), fontWeight = FontWeight.SemiBold) }
        }
    }) {
        if (!running) {
            item { TimeTiles { playback.sleep(it); close() } }
            item { ChapterCard(state.chapterTitle, chapterLeft) { playback.sleepAtChapterEnd(); close() } }
        } else {
            item { RunningTimer(state, left, chapterLeft, now, playback) }
            item {
                Text(tr("Change to"), Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SleepTimes.forEach { minutes ->
                        SmallChoice("$minutes", tr("$minutes min"), left != null && state.sleepMinutes == minutes, "sleep_$minutes") { playback.sleep(minutes); close() }
                    }
                    SmallChoice(tr("Chapter end"), tr("End of chapter"), state.sleepAtChapterEnd, "sleep_chapter") { playback.sleepAtChapterEnd(); close() }
                }
            }
        }
    }
}

@Composable
private fun TimeTiles(pick: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SleepTimes.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { minutes ->
                    Column(
                        Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainerHighest)
                            .clickable(role = Role.Button, onClickLabel = tr("$minutes min")) { pick(minutes) }
                            .semantics { contentDescription = tr("$minutes min") }.testTag("sleep_$minutes"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text("$minutes", style = MaterialTheme.typography.headlineMedium, color = colors.onSurface)
                        Text(tr("min"), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterCard(chapter: String, leftMs: Long, pick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp).fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp))
            .background(colors.surfaceContainerHighest).clickable(role = Role.Button, onClick = pick).testTag("sleep_chapter")
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(colors.primary.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_chapters), null, Modifier.size(20.dp), tint = colors.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(tr("End of chapter"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Text(tr(chapter), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Text(minutesLabel(leftMs), style = MaterialTheme.typography.titleMedium.copy(fontFamily = MaterialTheme.typography.titleLarge.fontFamily), color = colors.onSurface)
    }
}

@Composable
private fun RunningTimer(state: PlaybackState, left: Long?, chapterLeft: Long, now: Long, playback: PlaybackControls) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val shake = com.example.readalong.ui.theme.LocalAppSettings.current.shakeToReset
    val remaining = left ?: chapterLeft
    val fraction = if (left != null) left.toFloat() / maxOf(left, state.sleepMinutes * 60_000L, 1L)
        else (chapterLeft.toFloat() / (state.chapterDurationMs / state.speed.coerceAtLeast(.1f)).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val endsAt = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now + remaining))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(190.dp).semantics { contentDescription = "${tr("Time left")} ${countdown(remaining)}" }, contentAlignment = Alignment.Center) {
            val track = colors.surfaceContainerHighest
            val arc = colors.primary
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val stroke = Stroke(7.dp.toPx(), cap = StrokeCap.Round)
                drawArc(track, 0f, 360f, false, style = stroke)
                drawArc(arc, -90f, 360f * fraction, false, style = stroke)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_sleep_filled), null, Modifier.size(18.dp), tint = colors.primary)
                Text(countdown(remaining), style = MaterialTheme.typography.displaySmall, color = colors.onSurface)
                Text(if (left != null) "${tr("ends")} $endsAt" else tr("End of chapter"), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        if (left != null) Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetPill("+5 min", Modifier.weight(1f).testTag("sleep_add_5")) { playback.sleepMore(5) }
            SheetPill("+15 min", Modifier.weight(1f).testTag("sleep_add_15")) { playback.sleepMore(15) }
            if (state.sleepMinutes > 0) SheetPill("Restart", Modifier.weight(1f).testTag("sleep_restart"), primary = true) { playback.sleep(state.sleepMinutes) }
        }
        if (shake && left != null && state.sleepMinutes > 0) Row(
            Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(colors.primary.copy(alpha = .08f)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_shake), null, Modifier.size(26.dp), tint = colors.primary)
            Text(tr("Shake your phone to restart ${state.sleepMinutes} min"), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, color = colors.onSurface)
        }
    }
}

@Composable
private fun SheetPill(label: String, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.height(46.dp).clip(RoundedCornerShape(23.dp)).background(if (primary) colors.primary else colors.surfaceContainerHighest)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(tr(label), style = MaterialTheme.typography.labelLarge, color = if (primary) colors.onPrimary else colors.onSurface, maxLines = 1)
    }
}

@Composable
private fun SmallChoice(label: String, description: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primary.copy(alpha = .18f) else colors.surfaceContainerHighest)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }.testTag(tag)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.primary else colors.onSurface)
    }
}

/** "23:41", or "1:05:00" past an hour. */
internal fun countdown(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 999) / 1_000
    return if (seconds >= 3_600) "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

/** "12 min", "1h 5m" or "<1 min". */
private fun minutesLabel(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    return when { minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"; minutes > 0 -> "$minutes ${tr("min")}"; else -> "<1 ${tr("min")}" }
}
