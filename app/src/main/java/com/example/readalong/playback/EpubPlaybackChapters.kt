package com.example.readalong.playback

import com.example.readalong.data.epub.TimedEpubChapter

/** Audio before the first matched EPUB chapter shorter than this is folded into that chapter. */
private const val OPENING_MIN_MS = 5_000L

/**
 * Player chapters taken from the matched EPUB contents, so the player and the reader's Contents agree.
 * Unmatched entries (front matter, chapters without audio) have no position to seek to and are left out,
 * as are entries whose matched start isn't after the previous one. Empty when nothing is matched.
 */
fun epubPlaybackChapters(epub: List<TimedEpubChapter>, fileDurations: List<Long>, openingTitle: String): List<PlaybackChapter> {
    val total = fileDurations.sum()
    val starts = mutableListOf<Pair<String, Long>>()
    epub.forEach { chapter ->
        if (chapter.startMs in 0 until total && chapter.startMs > (starts.lastOrNull()?.second ?: -1)) starts += chapter.title to chapter.startMs
    }
    if (starts.isEmpty()) return emptyList()
    if (starts.first().second >= OPENING_MIN_MS) starts.add(0, openingTitle to 0L)
    else starts[0] = starts[0].first to 0L
    return starts.mapIndexed { index, (title, start) ->
        val end = starts.getOrNull(index + 1)?.second ?: total
        var fileIndex = 0
        var fileStart = start
        while (fileIndex < fileDurations.lastIndex && fileStart >= fileDurations[fileIndex]) fileStart -= fileDurations[fileIndex++]
        PlaybackChapter(index, title, start, end - start, fileIndex, fileStart)
    }
}
