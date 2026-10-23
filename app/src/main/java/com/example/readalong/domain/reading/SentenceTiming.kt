package com.example.readalong.domain.reading

import com.example.readalong.domain.model.TranscriptAnchor
import com.example.readalong.domain.model.TranscriptSegment
import com.example.readalong.domain.model.TranscriptWord
import java.util.Locale

data class SentenceCue(val start: Int, val end: Int, val audioStartMs: Long?, val audioEndMs: Long?, val estimated: Boolean = false) {
    fun activeAt(position: Long) = audioStartMs != null && audioEndMs != null && position >= audioStartMs && position < audioEndMs
}

/** Overlapping anchors must not leave an earlier sentence highlighted after the next begins. */
fun activeSentenceIndex(cues: List<SentenceCue>, position: Long): Int =
    cues.indices.filter { cues[it].activeAt(position) }.maxByOrNull { cues[it].audioStartMs!! } ?: -1

/** Include a small attack margin without replaying the previous spoken word. Highlighting
 * still uses the acoustic onset, rather than this slightly earlier seek position. */
fun sentenceSeekStart(cue: SentenceCue, sources: Collection<TranscriptSegment>): Long? {
    val onset = cue.audioStartMs ?: return null
    if (cue.estimated) return onset // The gap begins exactly at the preceding confirmed end.
    // Explicit manual points and legacy paragraph timestamps already represent the
    // chosen start. Apply the attack margin only to a saved acoustic word onset.
    if (sources.none { source -> source.words.any { it.startMs == onset } }) return onset
    val previousEnd = sources.asSequence().flatMap { it.words.asSequence() }
        .filter { it.startMs < onset && it.endMs <= onset }.maxOfOrNull { it.endMs } ?: 0L
    return maxOf(0L, onset - 160L, previousEnd)
}

private val sentenceWords = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
private val abbreviations = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e")
private fun wordKey(value: String) = value.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)

/** Character ranges only; publisher text, punctuation and whitespace are never rewritten. */
fun sentenceRanges(text: String): List<IntRange> {
    val result = mutableListOf<IntRange>()
    var start = 0
    var i = 0
    fun finish(end: Int) {
        while (start < end && text[start].isWhitespace()) start++
        var trimmed = end
        while (trimmed > start && text[trimmed - 1].isWhitespace()) trimmed--
        if (trimmed > start) result += start until trimmed
        start = end
    }
    while (i < text.length) {
        val c = text[i]
        if (c == '\n') { finish(i); start = i + 1 }
        else if (c in ".!?") {
            val token = text.substring(start, i).takeLastWhile { it.isLetter() || it == '.' }
            val abbreviation = c == '.' && (token.lowercase(Locale.ROOT) in abbreviations ||
                token.length == 1 && token[0].isUpperCase() ||
                i > 0 && text[i - 1].isDigit() && text.getOrNull(i + 1)?.isDigit() == true)
            if (!abbreviation) {
                var end = i + 1
                while (end < text.length && text[end] in ".!?\"'”’)]}") end++
                if (end == text.length || text[end].isWhitespace()) { finish(end); i = end - 1 }
            }
        }
        i++
    }
    finish(text.length)
    return result
}

/** Map small, already aligned spans to saved word times, allowing spelling corrections and omissions.
 * No time is interpolated by text length; unmatched text keeps a null audio locator. */
private fun wordMapping(text: String, source: List<TranscriptWord>): List<Pair<IntRange, TranscriptWord>> {
    val shown = sentenceWords.findAll(text).toList()
    val spoken = source.filter { wordKey(it.word).isNotEmpty() }
    if (shown.isEmpty() || spoken.isEmpty() || shown.size > 300 || spoken.size > 300) return emptyList()
    val a = shown.map { wordKey(it.value) }; val b = spoken.map { wordKey(it.word) }
    val cols = b.size + 1
    val cost = IntArray((a.size + 1) * cols)
    for (i in 0..a.size) cost[i * cols] = i
    for (j in 0..b.size) cost[j] = j
    for (i in 1..a.size) for (j in 1..b.size) cost[i * cols + j] = minOf(
        cost[(i - 1) * cols + j] + 1, cost[i * cols + j - 1] + 1,
        cost[(i - 1) * cols + j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
    if (cost.last() > maxOf(1.0, maxOf(a.size, b.size) * 0.4)) return emptyList()
    val mapped = mutableListOf<Pair<IntRange, TranscriptWord>>()
    var i = a.size; var j = b.size
    while (i > 0 && j > 0) {
        val substitution = if (a[i - 1] == b[j - 1]) 0 else 1
        when (cost[i * cols + j]) {
            cost[(i - 1) * cols + j - 1] + substitution -> { mapped += shown[i - 1].range to spoken[j - 1]; i--; j-- }
            cost[(i - 1) * cols + j] + 1 -> i--
            else -> j--
        }
    }
    return mapped.asReversed()
}

/** An EPUB paragraph can cover only part of a recognition segment. Restrict speech to the
 * exact source character range saved by the aligner before matching its displayed words. */
fun timedAnchorWords(text: String, anchor: TranscriptAnchor, source: TranscriptSegment?): List<Pair<IntRange, TranscriptWord>> {
    if (anchor.start !in 0..text.length || anchor.end !in anchor.start..text.length || source == null) return emptyList()
    val words = if (anchor.sourceStart != null && anchor.sourceEnd != null) {
        if (anchor.sourceStart !in 0..source.text.length || anchor.sourceEnd !in anchor.sourceStart..source.text.length) return emptyList()
        wordMapping(source.text, source.words).filter { (range, _) ->
            range.first >= anchor.sourceStart && range.last < anchor.sourceEnd
        }.map { it.second }
    } else source.words
    return wordMapping(text.substring(anchor.start, anchor.end), words).map { (range, word) ->
        (range.first + anchor.start..range.last + anchor.start) to word
    }
}

fun sentenceCues(paragraph: TranscriptSegment, sources: Map<String, TranscriptSegment> = emptyMap()): List<SentenceCue> {
    val ranges = sentenceRanges(paragraph.text)
    val anchors = paragraph.anchors.ifEmpty {
        if (!paragraph.imported && paragraph.startMs >= 0) listOf(TranscriptAnchor(paragraph.id, paragraph.startMs, paragraph.endMs, 0, paragraph.text.length)) else emptyList()
    }
    val confirmed = anchors.filterNot { it.estimated }
    val mapped = confirmed.flatMap { anchor ->
        val source = sources[anchor.id] ?: paragraph.takeIf { it.id == anchor.id && it.anchors.isEmpty() }
        timedAnchorWords(paragraph.text, anchor, source)
    }
    return ranges.map { range ->
        val located = mapped.filter { (r, _) -> r.first <= range.last && r.last >= range.first }
        val words = located.map { it.second }
        val opening = sentenceWords.find(paragraph.text, range.first)?.takeIf { it.range.first <= range.last }
        // Legacy/manual anchors can time one sentence, but cannot invent separate starts inside it.
        val fallback = confirmed.filter { it.start <= (opening?.range?.first ?: range.first) && it.start >= range.first &&
            (it.end <= range.last + 1 || sources[it.id]?.words.orEmpty().isEmpty()) }
        // Matching the middle of a sentence proves its location, not its beginning. A chunk
        // starting halfway through a publisher sentence must never become that sentence's start.
        val openingWords = located.filter { it.first.first == opening?.range?.first }
        val boundary = fallback.filter { anchor ->
            val source = sources[anchor.id]
            source == null || source.words.isEmpty() || anchor.sourceStart == 0
        }.minOfOrNull { it.startMs }
        val start = openingWords.minOfOrNull { it.second.startMs } ?: boundary
        val end = words.maxOfOrNull { it.endMs } ?: fallback.maxOfOrNull { it.endMs }
        val estimate = anchors.firstOrNull { it.estimated && it.start == range.first && it.end == range.last + 1 }
        if (start == null && estimate != null) SentenceCue(range.first, range.last + 1, estimate.startMs, estimate.endMs, estimated = true)
        else SentenceCue(range.first, range.last + 1, start, end?.takeIf { start != null && it > start })
    }
}

fun sentenceAt(cues: List<SentenceCue>, offset: Int): SentenceCue? =
    cues.firstOrNull { offset in it.start until it.end } ?: cues.firstOrNull { offset < it.start } ?: cues.lastOrNull()
