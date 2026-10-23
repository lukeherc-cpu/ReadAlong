package com.example.readalong.domain.transcription

import com.example.readalong.domain.model.TranscriptSegment
import kotlinx.coroutines.flow.Flow

/** One bounded unit of audio, with offsets in the canonical whole-book timeline. */
data class TranscriptionInput(val bookId: String, val chapterId: String, val audioUri: String?,
    val startMs: Long, val endMs: Long, val isDemo: Boolean, val fileStartMs: Long = 0)

interface TranscriptionEngine {
    val id: String
    fun transcribe(input: TranscriptionInput): Flow<TranscriptSegment>
}

enum class ProcessingState {
    WAITING_SELECTION, WAITING_MODEL, WAITING_QUEUE, QUEUED, PROCESSING, PARTIAL_READY, GENERATING_EPUB, READY, PREVIEW_READY, CANCELLED, FAILED;
    val active get() = this in setOf(QUEUED, PROCESSING, PARTIAL_READY, GENERATING_EPUB)
}
