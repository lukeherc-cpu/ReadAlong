package com.example.readalong.domain.reading

/**
 * Paces newly transcribed text so the reader appears to write it word by word, instead of a whole
 * ~30 second chunk appearing each time the speech model finishes one.
 *
 * The reader hands every loaded page of paragraphs to [accept]. Text that grew at the end of the book
 * (a new paragraph past the previous end, or more words on a paragraph the chunk continued) is queued.
 * [advance] moves one writing cursor through the queue at a pace that should finish just as the next
 * chunk arrives, so writing stays continuous whether the phone transcribes quickly or slowly.
 * Anything that isn't a plain append (a reload, a corrected passage, a big import) is shown at once.
 */
class LiveWriting(private val maxBacklog: Int = 4_000) {
    data class Entry(val index: Int, val id: String, val text: String)
    /** The paragraph whose words are being written now, and where in it the cursor is. */
    data class Frontier(val index: Int, val id: String, val position: Float)
    /** Text before [from] was already shown; the cursor is at [position] (characters, fractional). */
    data class Written(val from: Int, val position: Float)

    private class Range(val id: String, val index: Int, val from: Int, val to: Int, val queueStart: Float) {
        val queueEnd get() = queueStart + (to - from)
    }

    private val known = HashMap<String, String>()
    private val ranges = ArrayList<Range>()
    private val pendingIds = HashSet<String>()
    private var queued = 0f
    private var cursor = 0f
    private var count = -1
    private var lastGrowthMs = -1L
    /** Writing time left until the queue should be written, counted down by [advance]. */
    private var timeLeftMs = 0f
    /** Smoothed time between chunks; the writing pace is set from it. */
    var chunkIntervalMs = 20_000f
        private set

    val writing get() = cursor < queued
    val remaining get() = (queued - cursor).coerceAtLeast(0f)
    /** The last item the reader knew about before any new text: where someone waiting for more would be. */
    val lastKnownIndex get() = count - 1

    val frontier: Frontier? get() = ranges.firstOrNull { cursor < it.queueEnd }?.let { Frontier(it.index, it.id, position(it)) }

    fun isWriting(id: String) = id in pendingIds

    /** How far [id] is written, or null when the paragraph is fully shown. */
    fun written(id: String): Written? {
        if (id !in pendingIds) return null
        return ranges.firstOrNull { it.id == id && cursor < it.queueEnd }?.let { Written(it.from, position(it)) }
    }

    private fun position(range: Range) = range.from + (cursor - range.queueStart).coerceAtLeast(0f)

    /**
     * Takes the reader's loaded paragraphs ([entries]) and its total item count. Only when [animate]
     * (the book is being transcribed and the person is looking at its end) is new text queued; otherwise
     * everything is marked shown. Returns true when what is shown changed.
     */
    fun accept(entries: List<Entry>, total: Int, animate: Boolean, nowMs: Long): Boolean {
        val wasWriting = writing
        if (count < 0 || !animate) {
            finish()
            remember(entries)
            count = total
            return wasWriting
        }
        val added = ArrayList<Entry>()
        val starts = HashMap<String, Int>()
        for (entry in entries.sortedBy { it.index }) {
            val old = known[entry.id]
            when {
                // New paragraph past the previous end of the book.
                old == null && entry.index >= count -> { added += entry; starts[entry.id] = 0 }
                // A page that was simply loaded (scrolling), not new text.
                old == null || old == entry.text -> {}
                // A paragraph the new chunk continued, near the end.
                entry.text.length > old.length && entry.text.startsWith(old) && entry.index >= count - 3 -> {
                    added += entry; starts[entry.id] = old.length
                }
                // A rewritten paragraph (a correction, re-split text). Never leave it half written.
                entry.id in pendingIds -> { finish(); remember(entries); count = total; return true }
                else -> {}
            }
        }
        remember(entries)
        count = total
        if (added.isEmpty()) return false
        if (lastGrowthMs >= 0) {
            val interval = nowMs - lastGrowthMs
            if (interval in 1_000..180_000) chunkIntervalMs = chunkIntervalMs * .6f + interval * .4f
        }
        lastGrowthMs = nowMs
        // Finish a little before the next chunk is expected, so the writing is continuous.
        timeLeftMs = chunkIntervalMs * .85f
        for (entry in added) {
            val from = starts.getValue(entry.id)
            if (entry.text.length <= from) continue
            val range = Range(entry.id, entry.index, from, entry.text.length, queued)
            ranges += range
            pendingIds += entry.id
            queued = range.queueEnd
        }
        // Too much at once (an import, a long wait away from the screen): show it rather than make anyone wait.
        if (remaining > maxBacklog) finish()
        return true
    }

    /** Moves the cursor on by [elapsedMs]. Returns true when it moved. */
    fun advance(elapsedMs: Float): Boolean {
        if (!writing || elapsedMs <= 0f) return false
        // An even pace that ends on time; never crawl, never dump a paragraph at once.
        val rate = (remaining / (timeLeftMs.coerceAtLeast(500f) / 1000f)).coerceIn(MinCharsPerSecond, MaxCharsPerSecond)
        timeLeftMs -= elapsedMs
        cursor = (cursor + rate * elapsedMs / 1000f).coerceAtMost(queued)
        while (ranges.isNotEmpty() && cursor >= ranges.first().queueEnd) {
            val done = ranges.removeAt(0)
            if (ranges.none { it.id == done.id }) pendingIds -= done.id
        }
        if (!writing) finish()
        return true
    }

    /** Shows everything that is queued. */
    fun finish() {
        ranges.clear(); pendingIds.clear()
        queued = 0f; cursor = 0f
    }

    private fun remember(entries: List<Entry>) {
        // Keep only what's loaded (a few hundred paragraphs at most) plus text still being written.
        known.keys.retainAll(pendingIds)
        entries.forEach { known[it.id] = it.text }
    }

    companion object {
        const val MinCharsPerSecond = 9f
        const val MaxCharsPerSecond = 450f
        /** Characters over which a newly written word fades from transparent to full ink. */
        const val FadeChars = 12f

        /**
         * Words of [text] at or after the cursor [position] that are not yet fully inked, with their opacity:
         * a word starts to appear when the cursor reaches it and is full ink [FadeChars] later.
         */
        fun fadingWords(text: String, position: Float, from: Int = 0): List<Triple<Int, Int, Float>> {
            val result = ArrayList<Triple<Int, Int, Float>>()
            var i = maxOf(from, (position - FadeChars).toInt() - 40, 0)
            // Start at a word boundary.
            while (i in 1 until text.length && !text[i - 1].isWhitespace()) i++
            while (i < text.length) {
                while (i < text.length && text[i].isWhitespace()) i++
                if (i >= text.length) break
                val start = i
                while (i < text.length && !text[i].isWhitespace()) i++
                val alpha = ((position - start) / FadeChars).coerceIn(0f, 1f)
                if (alpha < 1f) result += Triple(start, i, alpha)
            }
            return result
        }
    }
}
