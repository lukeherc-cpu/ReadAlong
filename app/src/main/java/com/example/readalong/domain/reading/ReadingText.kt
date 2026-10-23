package com.example.readalong.domain.reading

import com.example.readalong.domain.model.TranscriptSegment

private val words = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
private fun normalizedWord(text: String) = text.filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)

data class FollowPreview(val text: String, val highlightStart: Int, val highlightEnd: Int)

/** Keeps the actual timed word/anchor visible rather than ellipsizing the start of a long paragraph. */
fun timedTextRange(segment: TranscriptSegment, position: Long, canonical: TranscriptSegment? = null): Pair<Int, Int> {
    val anchor = segment.anchors.firstOrNull { position in it.startMs until it.endMs }
    val start = anchor?.start ?: 0
    val end = anchor?.end ?: segment.text.length
    val source = canonical ?: segment.takeUnless { it.imported }
    var focusStart = start; var focusEnd = end
    if (source != null && start in 0..end && end <= segment.text.length) {
        val tokens = words.findAll(segment.text.substring(start, end)).toList()
        // Even when EPUB spelling differs, actual word gaps must not light up the whole phrase.
        val agrees = tokens.size == source.words.size && tokens.indices.all {
            normalizedWord(tokens[it].value) == normalizedWord(source.words[it].word)
        }
        if (!agrees && source.words.isNotEmpty() && source.words.none { position in it.startMs until it.endMs }) return start to start
        // Use word times only when the entire source word sequence agrees; never estimate a word position.
        if (agrees) {
            val active = source.words.indexOfFirst { position in it.startMs until it.endMs }
            if (active >= 0) { focusStart = start + tokens[active].range.first; focusEnd = start + tokens[active].range.last + 1 }
            else {
                // A segment can include introductory music or pauses between words. Keep its
                // locator, but do not highlight words that have not actually reached their timestamp.
                val previous = source.words.indexOfLast { it.endMs <= position }
                focusStart = start + if (previous >= 0) tokens[previous].range.last + 1 else tokens.firstOrNull()?.range?.first ?: 0
                focusEnd = focusStart
            }
        }
    }
    return focusStart to focusEnd
}

fun followPreview(segment: TranscriptSegment, position: Long, canonical: TranscriptSegment? = null): FollowPreview {
    val (focusStart, focusEnd) = timedTextRange(segment, position, canonical)
    var from = (focusStart - 45).coerceAtLeast(0)
    if (from > 0) while (from < focusStart && !segment.text[from - 1].isWhitespace()) from++
    val to = maxOf(focusEnd.coerceAtMost(focusStart + 240), focusStart + 200).coerceAtMost(segment.text.length)
    val prefix = if (from > 0) "… " else ""
    val text = prefix + segment.text.substring(from, to) + if (to < segment.text.length) " …" else ""
    return FollowPreview(text, prefix.length + focusStart - from,
        (prefix.length + focusEnd - from).coerceAtMost(prefix.length + to - from))
}

data class SearchPassage(val index: Int, val segment: TranscriptSegment)
data class PassageHit(val passage: SearchPassage, val offset: Int, val end: Int, val preview: String)

/** Streaming phrase search across paragraph boundaries, with original character locators retained. */
class PassageSearch(query: String) {
    private data class Ref(val passage: SearchPassage, val offset: Int)
    private data class Normalized(val text: String, val offsets: IntArray)
    private val needle = normalize(query).text.trim()
    private var tail = ""
    private var references = emptyList<Ref>()
    private val seen = mutableSetOf<Pair<String, Int>>()
    init { require(needle.length in 2..500) { "Enter a word or sentence (up to 500 characters)." } }
    fun feed(passage: SearchPassage): List<PassageHit> {
        val current = normalize(passage.segment.text)
        val prefix = if (tail.isEmpty()) "" else "$tail "
        val joined = prefix + current.text
        fun ref(index: Int): Ref? = when {
            index < tail.length -> references.getOrNull(index)
            index >= prefix.length -> current.offsets.getOrNull(index - prefix.length)?.let { Ref(passage, it) }
            else -> references.lastOrNull()
        }
        val hits = mutableListOf<PassageHit>()
        var offset = 0
        while (offset < joined.length && hits.size < 20) {
            val at = joined.indexOf(needle, offset); if (at < 0) break
            offset = at + 1
            if ((at > 0 && joined[at - 1] != ' ') || (at + needle.length < joined.length && joined[at + needle.length] != ' ')) continue
            val first = ref(at) ?: continue
            val last = ref(at + needle.length - 1) ?: continue
            if (seen.add(first.passage.segment.id to first.offset)) hits += PassageHit(first.passage, first.offset,
                if (last.passage.segment.id == first.passage.segment.id) last.offset + 1 else first.passage.segment.text.length,
                joined.substring((at - 45).coerceAtLeast(0), (at + needle.length + 90).coerceAtMost(joined.length)))
        }
        val from = (joined.length - needle.length).coerceAtLeast(0)
        val retained = (from until joined.length).mapNotNull { i -> ref(i)?.let { joined[i] to it } }
        tail = retained.joinToString("") { it.first.toString() }
        references = retained.map { it.second }
        return hits
    }
    private fun normalize(text: String): Normalized {
        val normalized = StringBuilder(); val offsets = ArrayList<Int>(text.length)
        text.forEachIndexed { i, c ->
            if (c.isLetterOrDigit()) { normalized.append(c.lowercaseChar()); offsets += i }
            else if (normalized.isNotEmpty() && normalized.last() != ' ') { normalized.append(' '); offsets += i }
        }
        while (normalized.isNotEmpty() && normalized.last() == ' ') { normalized.setLength(normalized.length - 1); offsets.removeAt(offsets.lastIndex) }
        return Normalized(normalized.toString(), offsets.toIntArray())
    }
}
