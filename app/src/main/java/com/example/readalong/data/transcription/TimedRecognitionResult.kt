package com.example.readalong.data.transcription

import com.example.readalong.domain.model.*
import com.example.readalong.domain.transcription.TranscriptionInput
import org.json.JSONArray

/** Keeps raw recognized wording. Confidence is an estimate, not a correctness guarantee. */
object TimedRecognitionResult {
    fun segments(json: String, input: TranscriptionInput): List<TranscriptSegment> {
        val rows = JSONArray(json)
        require(rows.length() <= 1000)
        val offsets = IntArray(rows.length())
        val flattened = mutableListOf<org.json.JSONObject>()
        for (index in 0 until rows.length()) {
            offsets[index] = flattened.size
            val tokens = rows.getJSONObject(index).optJSONArray("tokens") ?: JSONArray()
            require(tokens.length() <= 5000)
            for (t in 0 until tokens.length()) flattened += tokens.getJSONObject(t)
        }
        val duration = input.endMs - input.startMs
        val dtw = flattened.map { it.optLong("dtwMs", -1) }
        val spoken = flattened.map { it.getString("text").any(Char::isLetterOrDigit) }
        val speechTimes = dtw.filterIndexed { index, _ -> spoken[index] }
        val aligned = speechTimes.isNotEmpty() && speechTimes.all { it in 0..duration } && speechTimes.zipWithNext().all { (a, b) -> b >= a }
        fun tokenStart(index: Int, useAlignment: Boolean = aligned) = if (useAlignment) dtw[index] else flattened[index].getLong("startMs")
        fun tokenEnd(index: Int, useAlignment: Boolean = aligned): Long {
            if (!useAlignment) return flattened[index].getLong("endMs")
            val start = dtw[index]
            val oldDuration = (flattened[index].getLong("endMs") - flattened[index].getLong("startMs")).coerceAtLeast(0)
            val next = dtw.drop(index + 1).firstOrNull { it in start..duration }
            // Keep the previous endpoint estimate, shifted to the acoustic start, but never
            // run past the following token or this decoded unit. Retain the original text.
            return minOf(next ?: duration, if (oldDuration > 0) start + oldDuration else start)
        }
        var previousEnd = input.startMs
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            val text = row.getString("text").trim()
            if (text.isBlank() || row.optDouble("noSpeech", 0.0) > 0.8) return@mapNotNull null
            val tokens = row.optJSONArray("tokens") ?: JSONArray()
            val first = offsets[index]
            val speech = (first until first + tokens.length()).filter { spoken[it] }
            val firstSpeech = speech.firstOrNull() ?: first
            // Punctuation has text but no voice. Its heuristic duration can cover seconds of
            // music after a sentence, so it must never extend a word or a segment.
            val alignedEnd = if (aligned) speech.maxOfOrNull { tokenEnd(it) } ?: -1 else -1
            // Degenerate attention paths must not discard otherwise valid recognized text.
            val useAlignment = aligned && speech.isNotEmpty() && alignedEnd > tokenStart(firstSpeech)
            val start = (input.startMs + if (useAlignment) tokenStart(firstSpeech) else row.getLong("startMs")).coerceAtLeast(previousEnd)
            val end = (input.startMs + if (useAlignment) alignedEnd else row.getLong("endMs")).coerceAtMost(input.endMs)
            if (start < input.startMs || end <= start) return@mapNotNull null
            val words = mutableListOf<TranscriptWord>()
            var word = ""
            var wordStart = start
            var wordEnd = start
            var confidenceSum = 0f
            var confidenceCount = 0
            var wordHasAudio = false
            fun flush() {
                if (word.isNotBlank() && wordEnd >= wordStart) words += TranscriptWord(word.trim(), wordStart, wordEnd,
                    if (confidenceCount > 0) confidenceSum / confidenceCount else null)
                word = ""; confidenceSum = 0f; confidenceCount = 0; wordHasAudio = false
                wordEnd = start
            }
            for (t in 0 until tokens.length()) {
                val token = tokens.getJSONObject(t)
                val piece = token.getString("text")
                val tokenStart = (input.startMs + tokenStart(first + t, useAlignment)).coerceIn(start, end)
                val tokenEnd = (input.startMs + tokenEnd(first + t, useAlignment)).coerceIn(start, end)
                if (piece.startsWith(" ") && word.isNotEmpty()) flush()
                if (word.isEmpty()) wordStart = tokenStart
                word += piece
                if (spoken[first + t]) {
                    if (!wordHasAudio) wordStart = tokenStart
                    wordHasAudio = true
                    wordEnd = maxOf(wordEnd, tokenEnd)
                }
                val confidence = token.optDouble("confidence", Double.NaN)
                if (confidence.isFinite()) { confidenceSum += confidence.toFloat().coerceIn(0f, 1f); confidenceCount++ }
            }
            flush()
            previousEnd = end
            TranscriptSegment("${input.bookId}:${input.chapterId}:$start", input.bookId, input.chapterId, start, end, text,
                words.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat(), words)
        }
    }
}
