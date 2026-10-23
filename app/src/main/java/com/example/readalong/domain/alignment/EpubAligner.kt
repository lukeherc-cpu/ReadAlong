package com.example.readalong.domain.alignment

import com.example.readalong.domain.epub.EpubBlock
import com.example.readalong.domain.model.TranscriptAnchor
import com.example.readalong.domain.model.TranscriptSegment
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Unique phrase anchors + a monotonic chain + bounded edit alignment. No time/percentage interpolation. */
class EpubAligner {
    private val tokenPattern = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    private val combiningMarks = Regex("[\\p{M}'’]")
    private val numbers = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
    fun align(blocks: List<EpubBlock>, segments: List<TranscriptSegment>, checkpoint: () -> Unit = {}): AlignmentResult {
        val original = alignPass(blocks, segments, checkpoint)
        // Recovery is additive and bounded to the opening before the first accepted
        // passage. Repeated epigraphs later in a book cannot hide its opening here.
        val first = original.blocks.flatMap { b -> b.anchors.map { b.block.ordinal to it } }
            .minByOrNull { it.second.startMs } ?: return original
        if (first.second.startMs > 300_000) return original
        val boundary = blocks.indexOfFirst { it.ordinal == first.first }
        val speechBoundary = segments.indexOfFirst { it.id == first.second.id }
        if (boundary < 0 || speechBoundary < 0) return original
        val recovered = alignPass(blocks.take(boundary + 1), segments.take(speechBoundary + 1), checkpoint, recoverOpening = true)
        var additions = 0
        val merged = original.blocks.mapIndexed { index, block ->
            val extra = recovered.blocks.getOrNull(index)?.anchors.orEmpty().filter { candidate ->
                !block.block.kind.startsWith("H") && !block.block.text.trim().startsWith("[") &&
                    candidate.startMs <= first.second.startMs &&
                    (index < boundary || candidate.end <= first.second.start) &&
                    block.anchors.none { it.start < candidate.end && candidate.start < it.end }
            }
            additions += extra.size
            if (extra.isEmpty()) block else block.copy(anchors = (block.anchors + extra).sortedBy { it.startMs },
                confidence = maxOf(block.confidence, recovered.blocks[index].confidence))
        }
        return if (additions == 0) original else original.copy(blocks = merged)
    }

    private fun alignPass(blocks: List<EpubBlock>, segments: List<TranscriptSegment>, checkpoint: () -> Unit,
        recoverOpening: Boolean = false): AlignmentResult {
        val book = tokens(blocks.map { it.text }, checkpoint)
        val speech = tokens(segments.map { it.text }, checkpoint)
        val mapped = IntArray(speech.size) { -1 }; val scores = FloatArray(speech.size)
        val anchored = BooleanArray(speech.size)
        val width = 4
        val bookPhrases = phrases(book, width, checkpoint)
        val speechPhrases = phrases(speech, width, checkpoint)
        val pairs = speechPhrases.entries.mapNotNull { (hash, s) ->
            val b = bookPhrases[hash]
            if (s < 0 || b == null || b < 0 || (0 until width).any { speech[s + it].value != book[b + it].value }) null else Pair(s, b)
        }.sortedBy { it.first }
        val chain = monotonic(pairs)
        val anchors = mutableListOf<Pair<Int, Int>>()
        chain.forEach { pair ->
            if (anchors.isEmpty() || (pair.first >= anchors.last().first + width && pair.second >= anchors.last().second + width)) anchors += pair
        }
        fun gap(s0: Int, s1: Int, b0: Int, b1: Int) {
            checkpoint()
            val n = s1 - s0; val m = b1 - b0
            if (n <= 0 || m <= 0 || n > 512 || m > 512 || max(n, m) > min(n, m) * 3 + 16) return
            val stride = m + 1
            val costs = IntArray((n + 1) * stride)
            for (i in 1..n) costs[i * stride] = i * 2
            for (j in 1..m) costs[j] = j * 2
            for (i in 1..n) {
                checkpoint()
                for (j in 1..m) {
                    val similarity = similarity(speech[s0 + i - 1].value, book[b0 + j - 1].value)
                    costs[i * stride + j] = minOf(costs[(i - 1) * stride + j] + 2, costs[i * stride + j - 1] + 2,
                        costs[(i - 1) * stride + j - 1] + if (similarity == 1f) 0 else if (similarity > 0) 1 else 3)
                }
            }
            var i = n; var j = m
            while (i > 0 && j > 0) {
                val score = similarity(speech[s0 + i - 1].value, book[b0 + j - 1].value)
                val diagonal = costs[(i - 1) * stride + j - 1] + if (score == 1f) 0 else if (score > 0) 1 else 3
                when {
                    costs[i * stride + j] == diagonal -> {
                        if (score > 0) { mapped[s0 + i - 1] = b0 + j - 1; scores[s0 + i - 1] = score }; i--; j--
                    }
                    costs[i * stride + j] == costs[(i - 1) * stride + j] + 2 -> i--
                    else -> j--
                }
            }
        }
        if (anchors.isEmpty()) {
            // Small matching excerpts can still align, but a short phrase is never globally searched.
            if (speech.size <= 256 && book.size <= 256 && abs(speech.size - book.size) <= 2) gap(0, speech.size, 0, book.size)
        } else {
            val first = anchors.first()
            gap(max(0, first.first - 128), first.first, max(0, first.second - 128), first.second)
            anchors.forEachIndexed { index, (s, b) ->
                checkpoint()
                for (k in 0 until width) { mapped[s + k] = b + k; scores[s + k] = 1f; anchored[s + k] = true }
                if (index + 1 < anchors.size) gap(s + width, anchors[index + 1].first, b + width, anchors[index + 1].second)
            }
            val last = anchors.last()
            gap(last.first + width, min(speech.size, last.first + width + 128), last.second + width, min(book.size, last.second + width + 128))
        }
        val bySegment = speech.indices.groupBy { speech[it].owner }
        val output = mutableMapOf<Int, MutableList<TranscriptAnchor>>()
        val confidence = mutableMapOf<Int, MutableList<Float>>()
        var matchedSegments = 0; var matchedTokens = 0
        bySegment.forEach { (segmentIndex, tokenIndexes) ->
            checkpoint()
            var accepted = tokenIndexes.filter { mapped[it] >= 0 }
            if (accepted.isEmpty()) return@forEach
            val exact = accepted.count { scores[it] == 1f }
            var score = accepted.sumOf { scores[it].toDouble() } / tokenIndexes.size
            var trimmed = false
            // A short sentence between nearby unique phrase anchors has reliable local
            // context even when the recognizer misspells one of its words.
            val context = (max(0, tokenIndexes.first() - width)..min(anchored.lastIndex, tokenIndexes.last() + width)).any { anchored[it] }
            if (accepted.size.toFloat() / tokenIndexes.size < .8f || score < .82 || exact.toFloat() / tokenIndexes.size < .65f ||
                (exact < 4 && !(context && exact >= min(3, tokenIndexes.size)))) {
                // A spoken chapter heading may be joined to a correctly recognized sentence.
                // Salvage only a long verbatim run containing a unique phrase anchor. Keep
                // its source character range: the preceding heading is never its timestamp.
                val runs = mutableListOf<MutableList<Int>>()
                accepted.filter { scores[it] == 1f }.forEach { at ->
                    val previous = runs.lastOrNull()?.lastOrNull()
                    if (previous == null || at != previous + 1 || mapped[at] != mapped[previous] + 1) runs += mutableListOf(at)
                    else runs.last().add(at)
                }
                accepted = runs.filter { (it.size >= 12 && it.any { at -> anchored[at] }) || (recoverOpening && it.size >= 4 && context) }.maxByOrNull { it.size } ?: return@forEach
                score = 1.0
                trimmed = true
            }
            val first = mapped[accepted.first()]; val last = mapped[accepted.last()]
            if (last - first > max(32, tokenIndexes.size * 2 + 8) || blocks[book[first].owner].spine != blocks[book[last].owner].spine) return@forEach
            val segment = segments[segmentIndex]
            if (segment.startMs < 0 || segment.endMs <= segment.startMs) return@forEach
            matchedSegments++; matchedTokens += accepted.size
            accepted.groupBy { book[mapped[it]].owner }.forEach { (block, words) ->
                var start = book[mapped[words.first()]].start
                var sourceStart = speech[words.first()].start
                val end = book[mapped[words.last()]].end
                // Restore a short misrecognized opening (e.g. "Lord Tresting" -> "Trusting")
                // only when the rest of this recognized sentence already has a strong match.
                // Lowercase chunk continuations are deliberately left partial.
                val sentence = com.example.readalong.domain.reading.sentenceRanges(blocks[block].text)
                    .firstOrNull { start in it }
                val sourceOpening = com.example.readalong.domain.reading.sentenceRanges(segment.text).firstOrNull()
                if (!trimmed && sentence != null && words.first() == accepted.first() && sourceOpening != null && sourceStart in sourceOpening &&
                    words.first() - tokenIndexes.first() in 0..2 &&
                    segment.text.firstOrNull(Char::isLetter)?.isUpperCase() == true) {
                    val prefix = tokenPattern.findAll(blocks[block].text.substring(sentence.first, start)).toList()
                    if (prefix.size in 1..3) {
                        start = sentence.first + prefix.first().range.first
                        sourceStart = 0
                    }
                }
                output.getOrPut(block) { mutableListOf() } += TranscriptAnchor(segment.id, segment.startMs, segment.endMs, start, end,
                    sourceStart, speech[words.last()].end)
                confidence.getOrPut(block) { mutableListOf() } += score.toFloat()
            }
        }
        return AlignmentResult(blocks.mapIndexed { index, block -> AlignedBlock(block,
            output[index].orEmpty().sortedBy { it.startMs }, confidence[index]?.average()?.toFloat() ?: 0f) },
            matchedSegments, segments.size, matchedTokens, speech.size)
    }

    private data class Token(val value: String, val owner: Int, val start: Int, val end: Int)
    private fun tokens(texts: List<String>, checkpoint: () -> Unit): List<Token> {
        val result = ArrayList<Token>()
        texts.forEachIndexed { owner, text ->
            checkpoint()
            tokenPattern.findAll(text).forEach { match ->
                require(result.size < 600_000) { "This edition exceeds the alignment word limit. The generated transcript is still available." }
                val value = Normalizer.normalize(match.value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
                    .replace(combiningMarks, "")
                val number = value.toIntOrNull()
                val normalized = if (number != null && number in 0..20) numbers[number] else value
                result += Token(normalized, owner, match.range.first, match.range.last + 1)
            }
        }
        return result
    }
    private fun phrases(tokens: List<Token>, width: Int, checkpoint: () -> Unit): Map<Long, Int> {
        val result = HashMap<Long, Int>()
        for (i in 0..tokens.size - width) {
            if (i % 1000 == 0) checkpoint()
            var hash = 1125899906842597L
            for (j in 0 until width) { tokens[i + j].value.forEach { hash = hash * 31 + it.code }; hash = hash * 31 + 0 }
            result[hash] = if (result.containsKey(hash)) -1 else i
        }
        return result
    }
    private fun monotonic(pairs: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (pairs.isEmpty()) return emptyList()
        val tails = IntArray(pairs.size); val parents = IntArray(pairs.size) { -1 }; var length = 0
        pairs.indices.forEach { i ->
            var lo = 0; var hi = length
            while (lo < hi) { val mid = (lo + hi) / 2; if (pairs[tails[mid]].second < pairs[i].second) lo = mid + 1 else hi = mid }
            if (lo > 0) parents[i] = tails[lo - 1]
            tails[lo] = i; if (lo == length) length++
        }
        val chain = mutableListOf<Pair<Int, Int>>(); var at = tails[length - 1]
        while (at >= 0) { chain += pairs[at]; at = parents[at] }
        return chain.asReversed()
    }
    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (max(a.length, b.length) > 64) return 0f
        // Limit phonetic spelling recovery to bounded local gaps after unique phrases.
        // At least three consonants and a narrow length difference prevent weak matches.
        fun consonants(word: String): String = buildString {
            word.filter { it !in "aeiouy" }.forEach { c -> if (lastOrNull() != c) append(c) }
        }
        if (min(a.length, b.length) >= 5 && abs(a.length - b.length) <= 3 &&
            a.all { it in 'a'..'z' } && b.all { it in 'a'..'z' }) {
            val key = consonants(a)
            if (key.length >= 3 && key == consonants(b)) return .72f
        }
        val limit = if (min(a.length, b.length) >= 7) 2 else 1
        if (min(a.length, b.length) < 4 || abs(a.length - b.length) > limit) return 0f
        var previous = IntArray(b.length + 1) { it }; var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            if (current.minOrNull()!! > limit) return 0f
            val swap = previous; previous = current; current = swap
        }
        return if (previous[b.length] <= limit) .72f else 0f
    }
}

data class AlignedBlock(val block: EpubBlock, val anchors: List<TranscriptAnchor>, val confidence: Float)
data class AlignmentResult(val blocks: List<AlignedBlock>, val matchedSegments: Int, val totalSegments: Int, val matchedTokens: Int, val totalTokens: Int) {
    val coverage: Float get() = if (totalTokens == 0) 0f else matchedTokens.toFloat() / totalTokens
    val useful: Boolean get() = coverage >= .35f && (matchedSegments >= 2 || matchedTokens >= 12)
}
