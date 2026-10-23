package com.example.readalong.domain.transcription

data class PreparationProgress(val chapter: Int, val chapterPercent: Int, val bookPercent: Int) {
    val label get() = "Ch $chapter :$chapterPercent% / Full book $bookPercent%"
}

/** Audio-duration weighted progress. An inference percentage applies only to its current unit. */
fun preparationProgress(inputs: List<TranscriptionInput>, completed: Int, unitPercent: Int = 0,
    chapterId: String? = null): PreparationProgress {
    if (inputs.isEmpty()) return PreparationProgress(1, 0, 0)
    val done = completed.coerceIn(0, inputs.size)
    val current = inputs.getOrNull(done) ?: inputs.last()
    val chapters = inputs.map { it.chapterId }.distinct()
    val selected = chapterId?.takeIf { it in chapters } ?: current.chapterId
    fun duration(input: TranscriptionInput) = (input.endMs - input.startMs).coerceAtLeast(0).toDouble()
    fun ready(index: Int, input: TranscriptionInput) = when {
        index < done -> duration(input)
        index == done -> duration(input) * unitPercent.coerceIn(0, 100) / 100.0
        else -> 0.0
    }
    val total = inputs.sumOf(::duration)
    var readyBook = 0.0; var chapterTotal = 0.0; var chapterDone = 0.0
    inputs.forEachIndexed { index, input ->
        val value = ready(index, input); readyBook += value
        if (input.chapterId == selected) { chapterTotal += duration(input); chapterDone += value }
    }
    return PreparationProgress(chapters.indexOf(selected) + 1,
        if (chapterTotal > 0) (chapterDone * 100 / chapterTotal).toInt().coerceIn(0, 100) else 0,
        if (total > 0) (readyBook * 100 / total).toInt().coerceIn(0, 100) else 0)
}
