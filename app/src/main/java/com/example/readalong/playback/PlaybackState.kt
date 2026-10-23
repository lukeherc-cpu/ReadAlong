package com.example.readalong.playback

data class PlaybackState(
    val bookId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val sleepEndsAt: Long? = null,
    val sleepAtChapterEnd: Boolean = false,
    val error: String? = null,
    val chapters: List<PlaybackChapter> = emptyList(),
    val chapterIndex: Int = 0,
    val filePositionMs: Long? = null,
    val isLoading: Boolean = false,
) {
    val chapterStartMs get() = chapters.getOrNull(chapterIndex)?.startMs ?: chapterStart(positionMs)
    val chapterPositionMs get() = filePositionMs?.let { (it - (chapters.getOrNull(chapterIndex)?.fileStartMs ?: 0)).coerceAtLeast(0) } ?: (positionMs - chapterStartMs).coerceAtLeast(0)
    val chapterDurationMs get() = chapters.getOrNull(chapterIndex)?.durationMs ?: (durationMs - chapterStartMs).coerceIn(1, 1_800_000)
    val chapterTitle get() = chapters.getOrNull(chapterIndex)?.title ?: "Demo · Chapter ${positionMs / 1_800_000 + 1}"
}

val PlaybackSpeeds = listOf(.5f, .75f, .9f, 1f, 1.1f, 1.2f, 1.25f, 1.5f, 1.75f, 2f)

fun chapterStart(positionMs: Long, chapterLengthMs: Long = 1_800_000) = positionMs / chapterLengthMs * chapterLengthMs
fun clockTime(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
