package com.example.readalong.data.processing

import com.example.readalong.data.local.ProcessingJobEntity
import com.example.readalong.domain.transcription.PreparationProgress
import com.example.readalong.domain.transcription.preparationProgress

/** Native section updates are persisted, so UI and notifications share the same progress. */
fun ProcessingJobEntity.progress(chapterId: String? = null): PreparationProgress {
    val inputs = cachedPlan(planJson)
    val percent = if (state == "PROCESSING" && message?.contains("transcribing this audio section") == true)
        Regex("(\\d+)%$").find(message)?.groupValues?.get(1)?.toIntOrNull() ?: 0 else 0
    return preparationProgress(inputs, completedUnits, percent, chapterId)
}

/** Resolve the chapter being transcribed, independently of the playback chapter. */
suspend fun ProcessingJobEntity.chapterTitle(database: com.example.readalong.data.local.ReadAlongDatabase): String {
    val plan = cachedPlan(planJson)
    val id = (plan.getOrNull(completedUnits) ?: plan.lastOrNull())?.chapterId
    return database.mediaDao().chapters(bookId).firstOrNull { it.id == id }?.title?.takeIf { it.isNotBlank() }
        ?: database.mediaDao().audioFiles(bookId).firstOrNull { it.id == id }?.name?.substringBeforeLast('.')
        ?: "Chapter ${progress().chapter}"
}

suspend fun ProcessingJobEntity.notificationLabel(database: com.example.readalong.data.local.ReadAlongDatabase): String {
    val progress = progress()
    return "${chapterTitle(database)} · ${progress.chapterPercent}% / Full book ${progress.bookPercent}%"
}

// Native progress messages change many times per section; the immutable plan does not.
private val planCache = object : java.util.LinkedHashMap<String, List<com.example.readalong.domain.transcription.TranscriptionInput>>(5, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<com.example.readalong.domain.transcription.TranscriptionInput>>?) = size > 4
}
internal fun cachedPlan(json: String?): List<com.example.readalong.domain.transcription.TranscriptionInput> {
    if (json == null) return emptyList()
    synchronized(planCache) {
        return planCache.getOrPut(json) { runCatching { TranscriptionPlan.decode(json) }.getOrDefault(emptyList()) }
    }
}
