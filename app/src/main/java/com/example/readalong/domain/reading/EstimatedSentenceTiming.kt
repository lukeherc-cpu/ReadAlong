package com.example.readalong.domain.reading

import com.example.readalong.domain.model.TranscriptAnchor
import com.example.readalong.domain.model.TranscriptSegment

/** Derived EPUB locators only. Never modify the recognized words or either confirmed boundary. */
fun estimatedGapAnchors(
    paragraphs: List<TranscriptSegment>,
    cues: List<List<SentenceCue>>,
): Map<String, List<TranscriptAnchor>> {
    require(paragraphs.size == cues.size)
    val rows = paragraphs.flatMapIndexed { i, paragraph -> cues[i].map { paragraph to it } }
    val result = mutableMapOf<String, MutableList<TranscriptAnchor>>()
    var previous = -1
    rows.forEachIndexed { index, (paragraph, cue) ->
        if (cue.estimated || cue.audioStartMs == null || cue.audioEndMs == null) return@forEachIndexed
        if (previous >= 0 && index > previous + 1) {
            val (before, boundary) = rows[previous]
            val start = boundary.audioEndMs!!
            val end = cue.audioStartMs
            val gap = rows.subList(previous + 1, index)
            // No extrapolation into unprepared audio, chapter changes, headings, or large omissions.
            // These limits bound uncertainty; music within an otherwise valid gap remains an estimate.
            val valid = end > start && end - start <= 60_000 && gap.size <= 12 &&
                before.chapterId == paragraph.chapterId && gap.all { (p, c) ->
                    p.imported && p.chapterId == paragraph.chapterId && c.audioStartMs == null &&
                        (p.formatting?.kind == null || p.formatting.kind in setOf("P", "QUOTE", "LIST", "VERSE"))
                }
            if (valid) {
                val weights = gap.map { (p, c) ->
                    p.text.substring(c.start, c.end).count { !it.isWhitespace() }.coerceAtLeast(1).toLong()
                }
                val total = weights.sum()
                var used = 0L
                gap.forEachIndexed { i, (p, c) ->
                    val from = start + (end - start) * used / total
                    used += weights[i]
                    val until = start + (end - start) * used / total
                    if (until > from) result.getOrPut(p.id) { mutableListOf() }.add(
                        TranscriptAnchor("ESTIMATED_GAP:${p.id}:${c.start}", from, until, c.start, c.end, estimated = true))
                }
            }
        }
        previous = index
    }
    return result
}
