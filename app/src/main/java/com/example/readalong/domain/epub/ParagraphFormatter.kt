package com.example.readalong.domain.epub

import com.example.readalong.domain.model.TranscriptSegment

/** Conservative, bounded layout only: never guesses words, punctuation or speakers. */
class ParagraphFormatter(private val emit: (List<TranscriptSegment>) -> Unit) {
    private val pending = mutableListOf<TranscriptSegment>()
    private var characters = 0
    fun add(segment: TranscriptSegment) {
        val last = pending.lastOrNull()
        if (last != null && (last.chapterId != segment.chapterId || segment.startMs - last.endMs >= 1800 ||
            (characters >= 320 && last.text.trimEnd().lastOrNull() in listOf('.', '!', '?', '”', '"')) ||
            characters + segment.text.length > 900 || pending.size >= 12)) flush()
        pending += segment
        characters += segment.text.length + 1
    }
    fun flush() {
        if (pending.isNotEmpty()) emit(pending.toList())
        pending.clear(); characters = 0
    }
}
